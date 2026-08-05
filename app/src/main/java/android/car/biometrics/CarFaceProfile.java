package android.car.biometrics;

import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public class CarFaceProfile implements Parcelable {
    public String driverName;
    public int targetUserId;
    public float preferredTemp;
    public float[] embedding;

    public CarFaceProfile(String driverName, int targetUserId, float preferredTemp, float[] embedding) {
        this.driverName = driverName;
        this.targetUserId = targetUserId;
        this.preferredTemp = preferredTemp;
        this.embedding = embedding;
    }

    protected CarFaceProfile(Parcel in) {
        driverName = in.readString();
        targetUserId = in.readInt();
        preferredTemp = in.readFloat();
        embedding = in.createFloatArray();
    }

    public static final Creator<CarFaceProfile> CREATOR = new Creator<CarFaceProfile>() {
        @Override
        public CarFaceProfile createFromParcel(Parcel in) {
            return new CarFaceProfile(in);
        }

        @Override
        public CarFaceProfile[] newArray(int size) {
            return new CarFaceProfile[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(driverName);
        dest.writeInt(targetUserId);
        dest.writeFloat(preferredTemp);
        dest.writeFloatArray(embedding);
    }
}
