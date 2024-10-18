package com.baofu.downloader.m3u8;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class M3U8 implements Parcelable {

    private String mUrl;
    private List<M3U8Seg> mTsList;
    private float mTargetDuration;
    private int mInitSequence;
    private int mVersion = 3;
    private boolean mHasEndList;
    private int bandWidth;
    public byte[] encryptionKey;
    public String encryptionIV;
    public List<ChildM3u8> childM3u8s;
    public String filename;
    public Map<String,String> header;

    public M3U8() {
        this("");
    }

    public M3U8(String url) {
        mUrl = url;
        mInitSequence = 0;
        mTsList = new ArrayList<>();
    }

    public void addTs(M3U8Seg ts) {
        mTsList.add(ts);
    }

    public void setTargetDuration(float targetDuration) {
        mTargetDuration = targetDuration;
    }

    public void setVersion(int version) {
        mVersion = version;
    }

    public void setSequence(int sequence) {
        mInitSequence = sequence;
    }

    public void setHasEndList(boolean hasEndList) {
        mHasEndList = hasEndList;
    }

    public List<M3U8Seg> getTsList() {
        return mTsList;
    }

    public int getVersion() {
        return mVersion;
    }

    public float getTargetDuration() {
        return mTargetDuration;
    }

    public int getInitSequence() {
        return mInitSequence;
    }

    public boolean hasEndList() {
        return mHasEndList;
    }

    public int getBandWidth() {
        return bandWidth;
    }

    public void setBandWidth(int bandWidth) {
        this.bandWidth = bandWidth;
    }

    public long getDuration() {
        long duration = 0L;
        for (M3U8Seg ts : mTsList) {
            duration += ts.getDuration();
        }
        return duration;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof M3U8) {
            M3U8 m3u8 = (M3U8) obj;
            if (mUrl != null && mUrl.equals(m3u8.mUrl))
                return true;
        }
        return false;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.mUrl);
        dest.writeList(this.mTsList);
        dest.writeFloat(this.mTargetDuration);
        dest.writeInt(this.mInitSequence);
        dest.writeInt(this.mVersion);
        dest.writeByte(this.mHasEndList ? (byte) 1 : (byte) 0);
        dest.writeInt(this.bandWidth);
    }

    public void readFromParcel(Parcel source) {
        this.mUrl = source.readString();
        this.mTsList = new ArrayList<M3U8Seg>();
        source.readList(this.mTsList, M3U8Seg.class.getClassLoader());
        this.mTargetDuration = source.readFloat();
        this.mInitSequence = source.readInt();
        this.mVersion = source.readInt();
        this.mHasEndList = source.readByte() != 0;
        this.bandWidth = source.readInt();
    }

    protected M3U8(Parcel in) {
        this.mUrl = in.readString();
        this.mTsList = new ArrayList<M3U8Seg>();
        in.readList(this.mTsList, M3U8Seg.class.getClassLoader());
        this.mTargetDuration = in.readFloat();
        this.mInitSequence = in.readInt();
        this.mVersion = in.readInt();
        this.mHasEndList = in.readByte() != 0;
        this.bandWidth = in.readInt();
    }

    public static final Creator<M3U8> CREATOR = new Creator<M3U8>() {
        @Override
        public M3U8 createFromParcel(Parcel source) {
            return new M3U8(source);
        }

        @Override
        public M3U8[] newArray(int size) {
            return new M3U8[size];
        }
    };

    /**
     * m3u8文件里的子m3u8
     */
    public static class ChildM3u8{
        public String name;
        public String url;
    }
}
