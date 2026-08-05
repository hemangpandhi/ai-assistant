package android.car.biometrics;

import android.car.biometrics.CarFaceProfile;
import android.view.Surface;

/** @hide */
interface ICarBiometricService {
    void enrollFaceProfile(in String driverName, in float[] embedding, int targetUserId, float preferredTemp);
    boolean startBiometricEnrollment(int targetUserId, in Surface previewSurface);
    CarFaceProfile verifyFaceAndSwitchUser(in float[] currentFrameEmbedding);
    void startBiometricAuthentication(in Surface previewSurface);
    void stopBiometricAuthentication();
    List<CarFaceProfile> getAllProfiles();
    void deleteProfile(in String driverName);
}
