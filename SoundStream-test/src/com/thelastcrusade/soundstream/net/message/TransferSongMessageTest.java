/*
 * Copyright 2013 The Last Crusade ContactLastCrusade@gmail.com
 * 
 * This file is part of SoundStream.
 * 
 * SoundStream is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * SoundStream is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with SoundStream.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.thelastcrusade.soundstream.net.message;

import static com.thelastcrusade.soundstream.util.CustomAssert.assertChecksumsMatch;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;

import org.junit.Test;

public class TransferSongMessageTest extends SerializationTest<TransferSongMessage> {
    
    @Test
    public void testSerializePlayMessage() throws Exception {
        File file = new File("./assets/Jesse_normal_trimmed.wav");
        FileInputStream fis = new FileInputStream(file);
        byte[] bytes = new byte[fis.available()];
        fis.read(bytes);
        long songId = 132452L;
        TransferSongMessage oldMessage = new TransferSongMessage(songId, file.getName(), file.getCanonicalPath());
        TransferSongMessage newMessage = super.testSerializeMessage(oldMessage);
        fis.close();

        //compare the song information
        assertEquals(oldMessage.getSongId(),       newMessage.getSongId());
        assertEquals(oldMessage.getSongFileName(), newMessage.getSongFileName());

        //compare the song binary data
        assertChecksumsMatch(oldMessage.getFilePath(), newMessage.getFilePath());
    }
}
