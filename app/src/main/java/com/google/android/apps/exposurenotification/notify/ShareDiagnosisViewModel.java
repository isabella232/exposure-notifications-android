/*
 * Copyright 2020 Google LLC
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

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.Fragment;
import androidx.hilt.Assisted;
import androidx.hilt.lifecycle.ViewModelInject;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import com.google.android.apps.exposurenotification.R;
import com.google.android.apps.exposurenotification.common.Qualifiers.BackgroundExecutor;
import com.google.android.apps.exposurenotification.common.Qualifiers.LightweightExecutor;
import com.google.android.apps.exposurenotification.common.Qualifiers.ScheduledExecutor;
import com.google.android.apps.exposurenotification.common.SingleLiveEvent;
import com.google.android.apps.exposurenotification.common.TaskToFutureAdapter;
import com.google.android.apps.exposurenotification.common.time.Clock;
import com.google.android.apps.exposurenotification.home.ExposureNotificationViewModel.ExposureNotificationState;
import com.google.android.apps.exposurenotification.keyupload.Upload;
import com.google.android.apps.exposurenotification.keyupload.UploadController;
import com.google.android.apps.exposurenotification.keyupload.UploadController.UploadException;
import com.google.android.apps.exposurenotification.nearby.ExposureNotificationClientWrapper;
import com.google.android.apps.exposurenotification.network.Connectivity;
import com.google.android.apps.exposurenotification.network.DiagnosisKey;
import com.google.android.apps.exposurenotification.storage.DiagnosisEntity;
import com.google.android.apps.exposurenotification.storage.DiagnosisEntity.HasSymptoms;
import com.google.android.apps.exposurenotification.storage.DiagnosisEntity.Shared;
import com.google.android.apps.exposurenotification.storage.DiagnosisEntity.TestResult;
import com.google.android.apps.exposurenotification.storage.DiagnosisEntity.TravelStatus;
import com.google.android.apps.exposurenotification.storage.DiagnosisRepository;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationSharedPreferences;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationSharedPreferences.VaccinationStatus;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationStatusCodes;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.android.libraries.privateanalytics.PrivateAnalyticsEnabledProvider;
import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;
import org.threeten.bp.Duration;
import org.threeten.bp.LocalDate;

/**
 * View model for the Share Diagnosis flow.
 *
 * <p>Implements all aspects of sharing diagnosis and exposure keys with the keyserver, including:
 *
 * <ul>
 *   <li>Exchanging a Verification Code with the Verification Server to obtain a long(er) term token
 *   <li>Using that long term token to get the Verification Server to certify that our exposure keys
 *       are associated with a genuine diagnosis.
 *   <li>Transmitting this certification along with the exposure keys to the keyserver.
 *   <li>Storing the results of all these RPCs locally, so that the process may be resumed if
 *       abandoned by the user or failed.
 * </ul>
 */
public class ShareDiagnosisViewModel extends ViewModel {

  private static final String TAG = "ShareDiagnosisViewModel";

  // Keys for storing UI state data in the SavedStateHandle.
  @VisibleForTesting static final String SAVED_STATE_CODE_IS_INVALID =
      "ShareDiagnosisViewModel.SAVED_STATE_CODE_IS_INVALID";
  @VisibleForTesting static final String SAVED_STATE_CODE_IS_VERIFIED =
      "ShareDiagnosisViewModel.SAVED_STATE_CODE_IS_VERIFIED";
  @VisibleForTesting static final String SAVED_STATE_SHARE_ADVANCE_SWITCHER_CHILD =
      "ShareDiagnosisViewModel.SAVED_STATE_SHARE_ADVANCE_SWITCHER_CHILD";
  @VisibleForTesting static final String SAVED_STATE_VERIFIED_CODE =
      "ShareDiagnosisViewModel.SAVED_STATE_VERIFIED_CODE";
  @VisibleForTesting static final String SAVED_STATE_CODE_INPUT =
      "ShareDiagnosisViewModel.SAVED_STATE_CODE_INPUT";
  @VisibleForTesting static final String SAVED_STATE_CODE_INPUT_IS_ENABLED =
      "ShareDiagnosisViewModel.SAVED_STATE_CODE_INPUT_IS_ENABLED";

  public static final Set<ExposureNotificationState> EN_STATES_BLOCKING_SHARING_FLOW =
      ImmutableSet.of(ExposureNotificationState.DISABLED,
          ExposureNotificationState.FOCUS_LOST,
          ExposureNotificationState.STORAGE_LOW,
          ExposureNotificationState.PAUSED_USER_PROFILE_NOT_SUPPORT,
          ExposureNotificationState.PAUSED_NOT_IN_ALLOWLIST,
          ExposureNotificationState.PAUSED_HW_NOT_SUPPORT,
          ExposureNotificationState.PAUSED_EN_NOT_SUPPORT);

  public static final DiagnosisEntity EMPTY_DIAGNOSIS = DiagnosisEntity.newBuilder().build();
  public static final long NO_EXISTING_ID = -1;
  private static final Duration GET_TEKS_TIMEOUT = Duration.ofSeconds(10);

  private final DiagnosisRepository diagnosisRepository;
  private final UploadController uploadController;
  private final ExposureNotificationClientWrapper exposureNotificationClientWrapper;
  private final Resources resources;
  private final Connectivity connectivity;
  private final ExposureNotificationSharedPreferences exposureNotificationSharedPreferences;
  private final PrivateAnalyticsEnabledProvider privateAnalyticsEnabledProvider;
  private final Clock clock;

  private final MutableLiveData<Long> currentDiagnosisId = new MutableLiveData<>(NO_EXISTING_ID);

  private final MutableLiveData<Boolean> inFlightLiveData = new MutableLiveData<>(false);

  private final SingleLiveEvent<Void> deletedLiveEvent = new SingleLiveEvent<>();
  private final SingleLiveEvent<String> snackbarLiveEvent = new SingleLiveEvent<>();
  private final SingleLiveEvent<ApiException> resolutionRequiredLiveEvent = new SingleLiveEvent<>();
  private final SingleLiveEvent<Boolean> sharedLiveEvent = new SingleLiveEvent<>();
  private final SingleLiveEvent<Boolean> revealCodeStepEvent = new SingleLiveEvent<>();

  private final MutableLiveData<Optional<VaccinationStatus>> vaccinationStatusForUILiveData =
      new MutableLiveData<>(Optional.absent());

  private final ExecutorService backgroundExecutor;
  private final ExecutorService lightweightExecutor;
  private final ScheduledExecutorService scheduledExecutor;

  // We use this immutable empty diagnosis livedata until we have a "real" one saved in storage,
  // just to make it nicer for observers; they don't have to check for null.
  private final LiveData<DiagnosisEntity> emptyDiagnosisLiveData =
      new MutableLiveData<>(EMPTY_DIAGNOSIS);

  private final MutableLiveData<Step> currentStepLiveData = new MutableLiveData<>();
  private final Stack<Step> backStack = new Stack<>();

  private final MutableLiveData<String> verificationErrorLiveData = new MutableLiveData<>("");
  private final Context context;

  private SavedStateHandle savedStateHandle;
  private boolean isDeleteOpen = false;
  private boolean isCloseOpen = false;
  private boolean isCodeFromLinkUsed = false;
  private boolean resumingAndNotConfirmed = false;
  private boolean restoredFromSavedState;

  /**
   * Via this enum, this viewmodel expresses to observers (fragments) what step in the flow the
   * current diagnosis is on.
   */
  enum Step {
    BEGIN, CODE, ONSET, REVIEW, SHARED, NOT_SHARED, TRAVEL_STATUS, VIEW
  }

  @ViewModelInject
  public ShareDiagnosisViewModel(
      @ApplicationContext Context context,
      @Assisted SavedStateHandle savedStateHandle,
      UploadController uploadController,
      DiagnosisRepository diagnosisRepository,
      ExposureNotificationClientWrapper exposureNotificationClientWrapper,
      ExposureNotificationSharedPreferences exposureNotificationSharedPreferences,
      PrivateAnalyticsEnabledProvider privateAnalyticsEnabledProvider,
      Clock clock,
      Connectivity connectivity,
      @BackgroundExecutor ExecutorService backgroundExecutor,
      @LightweightExecutor ExecutorService lightweightExecutor,
      @ScheduledExecutor ScheduledExecutorService scheduledExecutor) {
    this.context = context;
    this.savedStateHandle = savedStateHandle;
    this.uploadController = uploadController;
    this.diagnosisRepository = diagnosisRepository;
    this.exposureNotificationClientWrapper = exposureNotificationClientWrapper;
    this.exposureNotificationSharedPreferences = exposureNotificationSharedPreferences;
    this.privateAnalyticsEnabledProvider = privateAnalyticsEnabledProvider;
    this.clock = clock;
    this.connectivity = connectivity;
    this.backgroundExecutor = backgroundExecutor;
    this.lightweightExecutor = lightweightExecutor;
    this.scheduledExecutor = scheduledExecutor;
    this.resources = context.getResources();
  }

  public static Set<String> getStepNames() {
    HashSet<String> stepNames = new HashSet<>();
    for (Step step : Step.values()) {
      stepNames.add(step.name());
    }
    return stepNames;
  }

  /**
   * Helper methods to make it easy for activities to add resolution handling for releasing TEKs.
   */
  public void registerResolutionForActivityResult(Fragment fragment) {
    ActivityResultLauncher<IntentSenderRequest> resolutionActivityResultLauncher =
        fragment.registerForActivityResult(
            new StartIntentSenderForResult(), this::onResolutionComplete);

    getResolutionRequiredLiveEvent()
        .observe(
            fragment,
            apiException -> startResolution(apiException, resolutionActivityResultLauncher));
  }

  public void onResolutionComplete(ActivityResult activityResult) {
    if (activityResult.getResultCode() == Activity.RESULT_OK) {
      // Okay to share, submit data.
      uploadKeys();
    } else {
      inFlightLiveData.setValue(false);
      setIsShared(Shared.NOT_ATTEMPTED);
    }
  }

  private void startResolution(ApiException apiException,
      ActivityResultLauncher<IntentSenderRequest> resolutionActivityResultLauncher) {
    PendingIntent resolution = apiException.getStatus().getResolution();
    if (resolution != null) {
      IntentSenderRequest intentSenderRequest =
          new IntentSenderRequest.Builder(resolution).build();
      resolutionActivityResultLauncher.launch(intentSenderRequest);
    }
  }

  public void setCurrentDiagnosisId(long id) {
    currentDiagnosisId.setValue(id);
  }

  public void postCurrentDiagnosisId(long id) {
    currentDiagnosisId.postValue(id);
  }

  public LiveData<Long> getCurrentDiagnosisId() {
    return currentDiagnosisId;
  }

  @NonNull
  public LiveData<DiagnosisEntity> getCurrentDiagnosisLiveData() {
    return Transformations.distinctUntilChanged(
        Transformations.switchMap(currentDiagnosisId, id -> {
          if (id > 0) {
            return diagnosisRepository.getByIdLiveData(id);
          }
          return emptyDiagnosisLiveData;
        }));
  }

  /**
   * Tells what step of the flow we're on based on the state of the current diagnosis.
   */
  public LiveData<Step> getCurrentStepLiveData() {
    return Transformations.distinctUntilChanged(currentStepLiveData);
  }

  /**
   * Tells what is the next step of the flow depending on the current step and current diagnosis.
   */
  public LiveData<Step> getNextStepLiveData(Step currentStep) {
    return Transformations.map(
        getCurrentDiagnosisLiveData(),
        diagnosis -> ShareDiagnosisFlowHelper.getNextStep(currentStep, diagnosis, context));
  }

  /**
   * Tells what is the previous step of the flow depending on the current step and current
   * diagnosis.
   */
  public LiveData<Step> getPreviousStepLiveData(Step currentStep) {
    return Transformations.map(
        getCurrentDiagnosisLiveData(),
        diagnosis -> ShareDiagnosisFlowHelper.getPreviousStep(currentStep, diagnosis, context));
  }

  /**
   * Notifies if the code verification has failed with a particular {@link String} error message.
   */
  public LiveData<String> getVerificationErrorLiveData() {
    return verificationErrorLiveData;
  }

  /**
   * Invalidate verification code error if the user inputs a verified code.
   */
  public void invalidateVerificationError() {
    verificationErrorLiveData.postValue("");
  }

  /**
   * Moves to the given {@link Step} and re-initializes the back stack if step is non-null.
   */
  public void nextStepIrreversible(@Nullable Step nextStep) {
    if (nextStep == null) {
      return;
    }
    backStack.clear();
    nextStep(nextStep);
  }

  /**
   * Moves to the given {@link Step} and adds to back stack if step is non-null.
   */
  public void nextStep(@Nullable Step nextStep) {
    if (nextStep == null) {
      return;
    }
    backStack.push(nextStep);
    currentStepLiveData.postValue(nextStep);
  }

  /**
   * Moves to the given {@link Step} and updates the back stack if step is non-null.
   * <p>
   * If previous step is not corresponding to the back stack previous, the back stack is cleared.
   */
  public void previousStep(@Nullable Step previousStep) {
    if (previousStep == null) {
      return;
    }
    backStack.pop();
    if (backStack.isEmpty()) {
      // No more to go back, set new step as the stack
      backStack.push(previousStep);
    } else {
      // Is more to go back, check if top equals where we are going
      // If so, do nothing new top of stack is good.
      // If not, clear the stack and reset to the previousStep.
      if (!backStack.peek().equals(previousStep)) {
        backStack.clear();
        backStack.push(previousStep);
      }
    }
    currentStepLiveData.postValue(previousStep);
  }

  /**
   * Moves back in the back step if possible.
   *
   * @return false if the back stack is empty and so not possible.
   */
  public boolean backStepIfPossible() {
    backStack.pop();
    if (backStack.isEmpty()) {
      return false;
    }
    currentStepLiveData.postValue(backStack.peek());
    return true;
  }

  /**
   * Removes the current step (assume must be the top on the stack) from the back stack.
   */
  public void removeCurrentStepFromBackStack() {
    backStack.pop();
  }

  /**
   * Accepts the user's input that they either had or not had symptoms, or decline to answer.
   */
  public void setHasSymptoms(HasSymptoms hasSymptoms) {
    save(diagnosis -> diagnosis.toBuilder().setHasSymptoms(hasSymptoms).build());
  }

  /**
   * Accepts the user's input when their symptoms started. Implies they answered "yes" that they did
   * in fact have symptoms, so we set that date as well.
   */
  public void setSymptomOnsetDate(LocalDate onsetDate) {
    save(diagnosis -> diagnosis.toBuilder()
        .setHasSymptoms(HasSymptoms.YES)
        .setOnsetDate(onsetDate)
        .build());
  }

  public void setTravelStatus(TravelStatus travelStatus) {
    save(diagnosis -> diagnosis.toBuilder().setTravelStatus(travelStatus).build());
  }

  /**
   * Sets whether the diagnosis was shared or failed.
   */
  public ListenableFuture<Long> setIsShared(Shared isShared) {
    return save(diagnosis -> {
      sharedLiveEvent.postValue(Shared.SHARED.equals(isShared));
      return diagnosis.toBuilder().setSharedStatus(isShared).build();
    });
  }

  /**
   * Tells us which vaccination status radio button was checked to restore state.
   * Null indicated no checked button.
   */
  public LiveData<Optional<VaccinationStatus>> getVaccinationStatusForUILiveData() {
    return Transformations.distinctUntilChanged(vaccinationStatusForUILiveData);
  }

  public void setVaccinationStatusForUI(VaccinationStatus vaccinationStatus) {
    vaccinationStatusForUILiveData.postValue(Optional.of(vaccinationStatus));
  }

  private ListenableFuture<Long> save(
      Function<DiagnosisEntity, DiagnosisEntity> mutator) {
    return FluentFuture.from(getCurrentDiagnosis())
        // Apply the given mutation
        .transform(diagnosis -> diagnosisRepository.createOrMutateById(diagnosis.getId(), mutator),
            backgroundExecutor)
        .transform(diagnosisId -> {
          // Remember the diagnosis ID for further operations.
          postCurrentDiagnosisId(diagnosisId);
          return diagnosisId;
        }, lightweightExecutor);
  }

  private ListenableFuture<DiagnosisEntity> getCurrentDiagnosis() {
    return FluentFuture.from(diagnosisRepository.getByIdAsync(currentDiagnosisId.getValue()))
        .transform(diagnosis -> {
          if (diagnosis == null) {
            Log.d(TAG, "No diagnosis id [" + currentDiagnosisId
                + "] exists in storage. Returning a fresh empty one.");
            return EMPTY_DIAGNOSIS;
          }
          Log.d(TAG, "Got saved diagnosis for id " + currentDiagnosisId.getValue());
          return diagnosis;
        }, backgroundExecutor);
  }

  @NonNull
  public LiveData<Boolean> getInFlightLiveData() {
    return inFlightLiveData;
  }

  /**
   * Deletes a given entity
   */
  public void deleteEntity(DiagnosisEntity diagnosis) {
    Futures.addCallback(
        diagnosisRepository.deleteByIdAsync(diagnosis.getId()),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(@NullableDecl Void result) {
            currentDiagnosisId.postValue(NO_EXISTING_ID);
            deletedLiveEvent.postCall();
          }

          @Override
          public void onFailure(@NonNull Throwable t) {
            Log.w(TAG, "Failed to delete", t);
          }
        },
        lightweightExecutor);
  }

  /**
   * Share the keys.
   */
  public ListenableFuture<?> uploadKeys() {
    inFlightLiveData.postValue(true);

    return FluentFuture.from(getRecentKeys())
        .transform(
            this::toDiagnosisKeysWithTransmissionRisk, lightweightExecutor)
        .transformAsync(
            this::getCertAndUploadKeys, backgroundExecutor)
        .transform(
            unused -> {
              inFlightLiveData.postValue(false);
              return null;
            },
            lightweightExecutor)
        .catching(UploadException.class, ex -> {
          snackbarLiveEvent.postValue(ex.getUploadError().getErrorMessage(resources));
          inFlightLiveData.postValue(false);
          return null;
        }, lightweightExecutor)
        .catching(ApiException.class, ex -> {
          if (ex.getStatusCode() == ExposureNotificationStatusCodes.RESOLUTION_REQUIRED) {
            resolutionRequiredLiveEvent.postValue(ex);
            return null;
          } else {
            Log.w(TAG, "No RESOLUTION_REQUIRED in result", ex);
          }
          snackbarLiveEvent.postValue(resources.getString(R.string.generic_error_message));
          inFlightLiveData.postValue(false);
          return null;
        }, lightweightExecutor)
        .catching(Exception.class, ex -> {
          snackbarLiveEvent.postValue(resources.getString(R.string.generic_error_message));
          inFlightLiveData.postValue(false);
          return null;
        }, lightweightExecutor);
  }

  /**
   * Gets recent (initially 14 days) Temporary Exposure Keys from Google Play Services.
   */
  private ListenableFuture<List<TemporaryExposureKey>> getRecentKeys() {
    Log.d(TAG, "Getting current TEKs from EN API...");
    return TaskToFutureAdapter.getFutureWithTimeout(
        exposureNotificationClientWrapper.getTemporaryExposureKeyHistory(),
        GET_TEKS_TIMEOUT,
        scheduledExecutor);
  }

  /**
   * Transforms from EN API's TEK object to our network package's expression of it, applying a
   * default transmission risk. This default TR is temporary, while we determine that part of the EN
   * API's contract.
   */
  private ImmutableList<DiagnosisKey> toDiagnosisKeysWithTransmissionRisk(
      List<TemporaryExposureKey> recentKeys) {
    Log.d(TAG, "Converting TEKs into DiagnosisKeys...");
    ImmutableList.Builder<DiagnosisKey> builder = new Builder<>();
    for (TemporaryExposureKey k : recentKeys) {
      builder.add(
          DiagnosisKey.newBuilder()
              .setKeyBytes(k.getKeyData())
              .setIntervalNumber(k.getRollingStartIntervalNumber())
              .setRollingPeriod(k.getRollingPeriod())
              // Accepting the default transmission risk for now, which the DiagnosisKey.Builder
              // comes with pre-set.
              .build());
    }
    return builder.build();
  }

  @AutoValue
  abstract static class EnterCodeStepReturnValue {

    static EnterCodeStepReturnValue create(boolean revealPage,
        Optional<String> verificationCodeToPrefill) {
      return new AutoValue_ShareDiagnosisViewModel_EnterCodeStepReturnValue(revealPage,
          verificationCodeToPrefill);
    }

    abstract boolean revealPage();

    abstract Optional<String> verificationCodeToPrefill();
  }

  /**
   * Checks if we are entering the Code step for the deep link flow. Call this function in
   * {@link ShareDiagnosisCodeFragment#onViewCreated(View, Bundle)}.
   *
   * @param codeFromLink code from the deep link.
   */
  public EnterCodeStepReturnValue enterCodeStep(@Nullable String codeFromLink) {
    if (Strings.isNullOrEmpty(codeFromLink)) {
      return EnterCodeStepReturnValue.create(true, Optional.absent());
    }

    // Not the first time user navigates to 'Code' step and the verification code from the link has
    // been used earlier. In this case, we deem the code was not accepted due to errors thus we
    // reveal the normal 'Code' page.
    if (isCodeFromLinkUsed()) {
      return EnterCodeStepReturnValue.create(true, Optional.absent());
    }

    setCodeFromUrlUsed();
    setCodeInputForCodeStep(codeFromLink);
    markUiToBeRestoredFromSavedState(true);
    submitCode(codeFromLink, true);
    return EnterCodeStepReturnValue.create(false, Optional.of(codeFromLink));
  }

  public ListenableFuture<?> submitCode(String code, boolean isCodeFromLink) {
    if (inFlightLiveData.getValue()) {
      return Futures.immediateVoidFuture();
    }
    inFlightLiveData.setValue(true);

    Log.d(TAG, "Checking verification code locally");
    return FluentFuture.from(diagnosisRepository.getByVerificationCodeAsync(code))
        .transformAsync(diagnosisEntities -> {
          if (!diagnosisEntities.isEmpty()) {
            // Should only be 1, but to be sure just choose the first.
            return Futures.immediateFailedFuture(
                new VerificationCodeExistsException(diagnosisEntities.get(0)));
          }
          if (!connectivity.hasInternet()) {
            return Futures.immediateFailedFuture(new NoInternetException());
          }
          Log.d(TAG, "Submitting verification code...");
          // Submit the verification code to the verification server:
          Upload upload = Upload.newBuilder(code).build();
          return uploadController.submitCode(upload);
        }, lightweightExecutor)
        .transformAsync(
            verifiedUpload -> {
              // If successful, capture the long term token and some diagnosis facts into storage.
              DiagnosisEntity.Builder builder =
                  DiagnosisEntity.newBuilder().setVerificationCode(code)
                      .setIsCodeFromLink(isCodeFromLink)
                      .setSharedStatus(Shared.NOT_ATTEMPTED);
              // The long term token is required.
              builder.setLongTermToken(verifiedUpload.longTermToken());
              // Symptom onset may or may not be provided by the verification server.
              if (verifiedUpload.symptomOnset() != null) {
                builder.setIsServerOnsetDate(true);
                builder.setOnsetDate(verifiedUpload.symptomOnset())
                    .setHasSymptoms(HasSymptoms.YES);
              }
              // Test type is currently always provided by the verification server, but that seems
              // like something that could change. Let's check.
              if (verifiedUpload.testType() != null) {
                builder.setTestResult(TestResult.of(verifiedUpload.testType()));
              }
              if (isResumingAndNotConfirmed()) {
                setResumingAndNotConfirmed(false);
              }
              // Store in the preferences that a verification code has been successfully uploaded.
              exposureNotificationSharedPreferences
                  .setPrivateAnalyticsLastSubmittedCodeTime(clock.now());
              return diagnosisRepository.upsertAsync(builder.build());
            },
            backgroundExecutor)
        .transformAsync(
            newDiagnosisId -> {
              // Remember the diagnosis ID as the "current" diagnosis for the rest of the sharing
              // flow.
              Log.d(TAG, "Current diagnosis stored, notifying view");
              postCurrentDiagnosisId(newDiagnosisId);
              inFlightLiveData.postValue(false);
              verificationErrorLiveData.postValue("");
              return diagnosisRepository.getByIdAsync(newDiagnosisId);
            }, lightweightExecutor)
        .transform(
            diagnosisEntity -> {
              if (ShareDiagnosisFlowHelper.isCodeStepSkippable(diagnosisEntity)) {
                skipCodeStep(diagnosisEntity);
              } else {
                revealCodeStepEvent.postValue(true);
                nextStep(ShareDiagnosisFlowHelper.getNextStep(Step.CODE, diagnosisEntity, context));
              }
              return null;
            }, lightweightExecutor)
        .catching(VerificationCodeExistsException.class, ex -> {
          inFlightLiveData.postValue(false);
          postCurrentDiagnosisId(ex.getDiagnosisEntity().getId());
          if (Shared.SHARED.equals(ex.getDiagnosisEntity().getSharedStatus())) {
            verificationErrorLiveData.postValue(
                resources.getString(R.string.code_error_already_submitted));
          }
          if (ShareDiagnosisFlowHelper.isCodeStepSkippable(ex.getDiagnosisEntity())) {
            skipCodeStep(ex.getDiagnosisEntity());
          } else {
            revealCodeStepEvent.postValue(true);
          }
          return null;
        }, lightweightExecutor)
        .catching(NoInternetException.class, ex -> {
          inFlightLiveData.postValue(false);
          verificationErrorLiveData.postValue(
              resources.getString(R.string.share_error_no_internet));
          revealCodeStepEvent.postValue(true);
          return null;
        }, lightweightExecutor)
        .catching(UploadException.class, ex -> {
          inFlightLiveData.postValue(false);
          revealCodeStepEvent.postValue(true);
          verificationErrorLiveData.postValue(ex.getUploadError().getErrorMessage(resources));
          return null;
        }, lightweightExecutor)
        .catching(Exception.class, ex -> {
          Log.e(TAG, "Failed to submit verification code.", ex);
          inFlightLiveData.postValue(false);
          revealCodeStepEvent.postValue(true);
          verificationErrorLiveData.postValue(resources.getString(R.string.generic_error_message));
          return null;
        }, lightweightExecutor);
  }

  void skipCodeStep(DiagnosisEntity diagnosisEntity) {
    removeCurrentStepFromBackStack();
    nextStep(ShareDiagnosisFlowHelper.getNextStep(Step.CODE, diagnosisEntity, context));
  }

  /**
   * An {@link Exception} for when the verification code already exists in the database.
   */
  private static class VerificationCodeExistsException extends Exception {

    private final DiagnosisEntity diagnosisEntity;

    public VerificationCodeExistsException(DiagnosisEntity diagnosisEntity) {
      this.diagnosisEntity = diagnosisEntity;
    }

    public DiagnosisEntity getDiagnosisEntity() {
      return diagnosisEntity;
    }
  }

  /**
   * An {@link Exception} thrown when there is no internet connectivity during code submission.
   */
  private static class NoInternetException extends Exception {

  }

  /**
   * Submits TEKs and our diagnosis for sharing to other EN participating devices.
   *
   * <p>This involves these steps:
   * <ol>
   *   <li>Grab some info from the current diagnosis in local storage.
   *   <li>Submit our TEKs to the verification server to get them signed (obtain a cert).
   *   <li>Submit the TEKs, the cert, and some other metadata to the keyserver
   * </ol>
   *
   * <p>In order to support resumption of past partially-successful diagnosis sharing flows, this
   * series of operations tries to look at the state of the stored diagnosis and do the right thing.
   *
   * @return a {@link ListenableFuture} of type {@link Boolean} of successfully submitted state
   */
  private ListenableFuture<?> getCertAndUploadKeys(ImmutableList<DiagnosisKey> diagnosisKeys) {
    Log.d(TAG, "Certifying and uploading keys...");
    return FluentFuture.from(getCurrentDiagnosis())
        .transform(
            // Construct an Upload from some diagnosis fields.
            diagnosis ->
                Upload.newBuilder(diagnosisKeys, diagnosis.getVerificationCode())
                    .setLongTermToken(diagnosis.getLongTermToken())
                    .setSymptomOnset(diagnosis.getOnsetDate())
                    .setCertificate(diagnosis.getCertificate())
                    .setHasTraveled(TravelStatus.TRAVELED.equals(diagnosis.getTravelStatus()))
                    .build(),
            lightweightExecutor)
        .transformAsync(
            upload -> {
              Log.d(TAG, "Submitting keys to verification server for certificate...");
              // We normally do not have a certificate yet, but in some cases like resuming a past
              // failed upload, we have one already. Get one if we need one.
              if (Strings.isNullOrEmpty(upload.certificate())) {
                return uploadController.submitKeysForCert(upload);
              }
              return Futures.immediateFuture(upload);
            },
            backgroundExecutor)
        .transformAsync(
            this::addRevisionTokenToUpload, lightweightExecutor)
        .transformAsync(
            upload -> {
              Log.d(TAG, "Uploading keys and cert to keyserver...");
              // Finally, the verification server having certified our diagnosis, upload our keys.
              return uploadController.upload(upload);
            },
            backgroundExecutor)
        .transform(
            upload -> {
              // Successfully submitted
              Log.d(TAG, "Upload success: " + upload);
              save(
                  diagnosis -> diagnosis.toBuilder()
                      .setCertificate(upload.certificate())
                      .setRevisionToken(upload.revisionToken())
                      .setSharedStatus(Shared.SHARED)
                      .build());
              sharedLiveEvent.postValue(true);
              // Store in the preferences that keys have been successfully uploaded.
              exposureNotificationSharedPreferences
                  .setPrivateAnalyticsLastSubmittedKeysTime(clock.now());
              return Shared.SHARED;
            },
            lightweightExecutor)
        .catching(
            ApiException.class,
            e -> {
              // Not successfully submitted
              Log.e(TAG, "Upload fail: ", e);
              setIsShared(Shared.NOT_SHARED);
              return Shared.NOT_SHARED;
            },
            lightweightExecutor);
  }

  private ListenableFuture<Upload> addRevisionTokenToUpload(Upload upload) {
    return FluentFuture.from(diagnosisRepository.getMostRecentRevisionTokenAsync())
        .transform(
            revisionToken -> upload.toBuilder().setRevisionToken(revisionToken).build(),
            backgroundExecutor);
  }

  /**
   * A {@link SingleLiveEvent} to trigger a snackbar.
   */
  public SingleLiveEvent<String> getSnackbarSingleLiveEvent() {
    return snackbarLiveEvent;
  }

  /**
   * A {@link SingleLiveEvent} that signifies a successful deletion.
   */
  public SingleLiveEvent<Void> getDeletedSingleLiveEvent() {
    return deletedLiveEvent;
  }

  /**
   * A {@link SingleLiveEvent} that returns {@link ApiException} to help with starting the
   * resolution.
   */
  public SingleLiveEvent<ApiException> getResolutionRequiredLiveEvent() {
    return resolutionRequiredLiveEvent;
  }

  /**
   * A {@link SingleLiveEvent} that returns {@link Boolean} to control whether the 'Code' step view
   * should be revealed.
   */
  public SingleLiveEvent<Boolean> getRevealCodeStepEvent() {
    return revealCodeStepEvent;
  }

  /**
   * A {@link SingleLiveEvent} that returns {@link Boolean} to show whether the data was shared.
   */
  public SingleLiveEvent<Boolean> getSharedLiveEvent() {
    return sharedLiveEvent;
  }

  public void setCloseOpen(boolean isCloseOpen) {
    this.isCloseOpen = isCloseOpen;
  }

  public boolean isCloseOpen() {
    return isCloseOpen;
  }

  public void setDeleteOpen(boolean isDeleteOpen) {
    this.isDeleteOpen = isDeleteOpen;
  }

  public boolean isDeleteOpen() {
    return isDeleteOpen;
  }

  public void setCodeFromUrlUsed() {
    isCodeFromLinkUsed = true;
  }

  public boolean isCodeFromLinkUsed() {
    return isCodeFromLinkUsed;
  }

  public boolean isTravelStatusStepSkippable() {
    return ShareDiagnosisFlowHelper.isTravelStatusStepSkippable(context);
  }

  public void setResumingAndNotConfirmed(boolean resumingAndNotConfirmed) {
    this.resumingAndNotConfirmed = resumingAndNotConfirmed;
  }

  public boolean isResumingAndNotConfirmed() {
    return resumingAndNotConfirmed;
  }

  /**
   * Sets a boolean flag to indicate if the UI for the Code step in the sharing flow should be
   * marked as restored from {@link SavedStateHandle} upon the next render.
   *
   * @param restoredFromSavedState whether the Code step UI should be marked as restored from
   *                               saved state upon the next render
   */
  public void markUiToBeRestoredFromSavedState(boolean restoredFromSavedState) {
    this.restoredFromSavedState = restoredFromSavedState;
  }

  /**
   * Checks whether the UI for the Code step in the sharing flow has been restored from
   * {@link SavedStateHandle} (which is the case if the UI has been restored e.g. upon device
   * configuration changes such as rotations).
   *
   * @return a boolean indicating if the UI for the Code step has been restored from the saved state
   */
  public boolean isUiToBeRestoredFromSavedState() {
    return restoredFromSavedState;
  }

  /*
   * All the methods below are to store or retrieve the different bits of UI state for the Code step
   * in the sharing flow with the help of SavedStateHandle.
   */
  public void setCodeIsInvalidForCodeStep(boolean isCodeInvalid) {
    savedStateHandle.set(SAVED_STATE_CODE_IS_INVALID, isCodeInvalid);
  }

  public LiveData<Boolean> isCodeInvalidForCodeStepLiveData() {
    return savedStateHandle.getLiveData(SAVED_STATE_CODE_IS_INVALID, false);
  }

  public void setCodeIsVerifiedForCodeStep(boolean isCodeVerified) {
    savedStateHandle.set(SAVED_STATE_CODE_IS_VERIFIED, isCodeVerified);
  }

  public LiveData<Boolean> isCodeVerifiedForCodeStepLiveData() {
    return savedStateHandle.getLiveData(SAVED_STATE_CODE_IS_VERIFIED, false);
  }

  public void setSwitcherChildForCodeStep(int displayedChild) {
    savedStateHandle.set(SAVED_STATE_SHARE_ADVANCE_SWITCHER_CHILD, displayedChild);
  }

  public LiveData<Integer> getSwitcherChildForCodeStepLiveData() {
    return savedStateHandle.getLiveData(SAVED_STATE_SHARE_ADVANCE_SWITCHER_CHILD, -1);
  }

  public void setCodeInputEnabledForCodeStep(boolean codeInputForCodeStepEnabled) {
    savedStateHandle.set(SAVED_STATE_CODE_INPUT_IS_ENABLED, codeInputForCodeStepEnabled);
  }

  public LiveData<Boolean> isCodeInputEnabledForCodeStepLiveData() {
    return savedStateHandle.getLiveData(SAVED_STATE_CODE_INPUT_IS_ENABLED, true);
  }

  public void setCodeInputForCodeStep(String codeInput) {
    savedStateHandle.set(SAVED_STATE_CODE_INPUT, codeInput);
  }

  public LiveData<String> getCodeInputForCodeStepLiveData() {
    return savedStateHandle.getLiveData(SAVED_STATE_CODE_INPUT, null);
  }

  public void setVerifiedCodeForCodeStep(@Nullable String verifiedCode) {
    savedStateHandle.set(SAVED_STATE_VERIFIED_CODE, verifiedCode);
  }

  public LiveData<String> getVerifiedCodeForCodeStepLiveData() {
    return savedStateHandle.getLiveData(SAVED_STATE_VERIFIED_CODE, null);
  }

  public void setLastVaccinationResponse(VaccinationStatus vaccinationStatus) {
    exposureNotificationSharedPreferences
        .setLastVaccinationResponse(clock.now(), vaccinationStatus);
  }

  /**
   * Check whether we show the vaccination question screen.
   * We only want to show it, if ALL of the following conditions are true:
   * - ENPA is supported by the App
   * - The user has enabled ENPA
   * - Vaccination question is enabled by the HealthAuthority (empty string indicates it being
   * disabled)
   */
  public boolean showVaccinationQuestion(Resources resources) {
    return privateAnalyticsEnabledProvider.isSupportedByApp()
        && privateAnalyticsEnabledProvider.isEnabledForUser()
        && !TextUtils.isEmpty(resources.getString(R.string.share_vaccination_detail));
  }

}
