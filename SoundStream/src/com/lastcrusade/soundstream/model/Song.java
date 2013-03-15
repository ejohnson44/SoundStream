package com.lastcrusade.soundstream.model;

public class Song {

    private SongMetadata metadata;

    private String filePath;
    private long size;
    private byte[] data;

    public Song() {}

    public Song(SongMetadata metadata) {
        this.metadata = metadata;
    }

    public String getFilePath() {
        return filePath;
    }
    
    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
    
    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public SongMetadata getMetadata() {
        return metadata;
    }

    public void setSongMetadata(SongMetadata metadata) {
        this.metadata = metadata;
    }
}