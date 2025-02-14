/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.google.android.apps.exposurenotification.notify;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;
import com.google.android.apps.exposurenotification.R;
import com.google.android.apps.exposurenotification.common.SnackbarUtil;
import com.google.android.apps.exposurenotification.databinding.FragmentSharingHistoryBinding;
import com.google.android.apps.exposurenotification.home.BaseFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Optional;
import dagger.hilt.android.AndroidEntryPoint;
import java.util.Objects;

/**
 * Sharing history Activity.
 */
@AndroidEntryPoint
public class SharingHistoryFragment extends BaseFragment {

  private static final String TAG = "SharingHistoryFragment";

  private FragmentSharingHistoryBinding binding;
  private NotifyHomeViewModel viewModel;

  /**
   * Creates a {@link SharingHistoryFragment} instance.
   */
  public static SharingHistoryFragment newInstance() {
    return new SharingHistoryFragment();
  }

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
    binding = FragmentSharingHistoryBinding.inflate(inflater, parent, false);
    return binding.getRoot();
  }

  public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    viewModel = new ViewModelProvider(this, getDefaultViewModelProviderFactory())
        .get(NotifyHomeViewModel.class);

    binding.home.setOnClickListener(v -> onBackPressed());

    DiagnosisEntityAdapter diagnosisEntityAdapter =
        new DiagnosisEntityAdapter(
            diagnosis -> transitionToFragmentWithBackStack(
                ShareDiagnosisFragment.newInstance(requireContext(), diagnosis)), viewModel);
    final LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
    binding.diagnosesRecyclerView.setLayoutManager(layoutManager);
    binding.diagnosesRecyclerView.setAdapter(diagnosisEntityAdapter);

    ItemTouchHelper itemTouchHelper = new ItemTouchHelper(
        new SwipeDeleteCallback(requireContext(), diagnosisEntityAdapter));
    itemTouchHelper.attachToRecyclerView(binding.diagnosesRecyclerView);

    viewModel
        .getAllDiagnosisEntityLiveData()
        .observe(getViewLifecycleOwner(), diagnosisEntities -> {
          binding.noSharingHistory.setVisibility(diagnosisEntities.isEmpty()
              ? View.VISIBLE : View.GONE);
          binding.diagnosesRecyclerView.setVisibility(diagnosisEntities.isEmpty()
              ? View.GONE : View.VISIBLE);
          diagnosisEntityAdapter.setDiagnosisEntities(diagnosisEntities);

          Optional<Integer> deleteDialogOpenAtPosition = viewModel.getDeleteDialogOpenAtPosition();
          if (deleteDialogOpenAtPosition.isPresent()) {
            showDeleteDialog(diagnosisEntityAdapter, deleteDialogOpenAtPosition.get());
          }
        });

    viewModel
        .getDeletedSingleLiveEvent()
        .observe(this, unused -> SnackbarUtil
            .maybeShowRegularSnackbar(getView(), getString(R.string.delete_test_result_confirmed)));
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }

  @Override
  public boolean onBackPressed() {
    getParentFragmentManager().popBackStack();
    return true;
  }

  private void showDeleteDialog(DiagnosisEntityAdapter adapter, int position) {
    viewModel.setDeleteDialogOpenAtPosition(position);
    new MaterialAlertDialogBuilder(requireContext(), R.style.ExposureNotificationAlertDialogTheme)
        .setTitle(R.string.delete_test_result_title)
        .setMessage(R.string.delete_test_result_detail)
        .setCancelable(true)
        .setNegativeButton(R.string.btn_cancel, (d, w) -> {
          d.cancel();
          viewModel.setDeleteDialogClosed();
          adapter.notifyDataSetChanged();
        })
        .setPositiveButton(
            R.string.btn_delete,
            (d, w) -> adapter.deleteDiagnosisEntity(position))
        .setOnDismissListener(d -> {
          viewModel.setDeleteDialogClosed();
          adapter.notifyDataSetChanged();
        })
        .setOnCancelListener(d -> {
          viewModel.setDeleteDialogClosed();
          adapter.notifyDataSetChanged();
        })
        .show();
  }

  class SwipeDeleteCallback extends ItemTouchHelper.SimpleCallback {

    private final ColorDrawable swipeBackground;
    private final Drawable deleteIcon;
    private final Paint swipeClear;
    private final int deleteIconHeightPx;
    private final int deleteIconMarginPx;

    private final DiagnosisEntityAdapter adapter;

    public SwipeDeleteCallback(Context context, DiagnosisEntityAdapter adapter) {
      super(0, ItemTouchHelper.LEFT);
      this.adapter = adapter;

      // Set a red background.
      swipeBackground = new ColorDrawable();
      swipeBackground.setColor(ContextCompat.getColor(context, R.color.delete));

      // Set the Paint object used to clear the Canvas if the swipe is cancelled.
      swipeClear = new Paint();
      swipeClear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

      // Set the delete icon.
      deleteIcon = DrawableCompat.wrap(
          Objects.requireNonNull(ContextCompat.getDrawable(context, R.drawable.ic_delete)));

      // Retrieve the delete icon's height and margin.
      deleteIconHeightPx = (int) getResources().getDimension(R.dimen.delete_icon_height);
      deleteIconMarginPx = (int) getResources().getDimension(R.dimen.delete_icon_margin);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull ViewHolder viewHolder,
        @NonNull ViewHolder target) {
      return false; // We don't need to support moving items up/down
    }

    @Override
    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
      int position = viewHolder.getAdapterPosition();
      showDeleteDialog(adapter, position);
    }

    @Override
    public void onChildDraw(
        @NonNull Canvas c, @NonNull RecyclerView recyclerView,
        @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY,
        int actionState, boolean isCurrentlyActive) {
      super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);

      View itemView = viewHolder.itemView;
      View itemDivider = itemView.findViewById(R.id.horizontal_divider_view);
      int position = viewHolder.getAdapterPosition();

      // A flag to check if the swipe is cancelled.
      boolean isCancelled = dX == 0 && !isCurrentlyActive;
      if (isCancelled) {
        // The swipe is cancelled. Clear the canvas and reset the divider visibility.
        c.drawRect(itemView.getRight() + dX, (float) itemView.getTop(),
            (float) itemView.getRight(), (float) itemView.getBottom(), swipeClear);
        itemDivider.setVisibility(position == adapter.getItemCount() - 1
            ? View.GONE : View.VISIBLE);
        return;
      }

      // Set a background with rounded corners for the itemView while swiping.
      itemView.setBackgroundResource(R.drawable.swipe_to_delete_item_view);
      // Hide the divider while swiping.
      itemDivider.setVisibility(View.GONE);

      // Draw the swipe background.
      int backgroundOffset =
          (int) getResources().getDimension(R.dimen.delete_item_view_corner_radius);
      swipeBackground.setBounds(itemView.getRight() - backgroundOffset + (int) dX,
          itemView.getTop(), itemView.getRight(), itemView.getBottom());
      swipeBackground.draw(c);

      // Draw the delete icon.
      int deleteIconTop = itemView.getTop() + (itemView.getHeight() - deleteIconHeightPx) / 2;
      int deleteIconBottom = deleteIconTop + deleteIconHeightPx;
      int deleteIconLeft = itemView.getRight() - deleteIconMarginPx - deleteIconHeightPx;
      int deleteIconRight = itemView.getRight() - deleteIconMarginPx;
      deleteIcon.setBounds(deleteIconLeft, deleteIconTop, deleteIconRight, deleteIconBottom);
      deleteIcon.draw(c);
    }
  }
}
