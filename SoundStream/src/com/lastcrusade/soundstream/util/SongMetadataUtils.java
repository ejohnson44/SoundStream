package com.lastcrusade.soundstream.util;

import com.lastcrusade.soundstream.model.SongMetadata;

public class SongMetadataUtils {

    /**
     * Create a unique key for this song.  This unique key consists of:
     *  Mac address (uniquely identifies a device)
     *  Song id (uniquely identifies a song on the device)
     * 
     * @param song
     * @return
     */
    public static String getUniqueKey(SongMetadata song) {
        return getUniqueKey(song.getMacAddress(), song.getId());
    }
    
    public static String getUniqueKey(String songSourceAddress, long songId) {
        return songSourceAddress.replace(":", "") + "_" + songId;
    }
}