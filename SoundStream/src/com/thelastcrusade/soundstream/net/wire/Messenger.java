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

package com.thelastcrusade.soundstream.net.wire;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import android.annotation.SuppressLint;
import android.util.Log;

import com.thelastcrusade.soundstream.net.message.IFileMessage;
import com.thelastcrusade.soundstream.net.message.IMessage;
import com.thelastcrusade.soundstream.net.message.MessageFormat;
import com.thelastcrusade.soundstream.net.wire.PacketFormat.ControlCode;
import com.thelastcrusade.soundstream.util.InputBuffer;
import com.thelastcrusade.soundstream.util.LogUtil;

/**
 * This class is the main entry point to send and receive messages in Sound Stream.  It implements a protocol
 * that represent serializable messages and file data in a series of packets.  This protocol is constructed
 * to allow multiple messages to be in transit at a time.  This means that control messages can be sent
 * while also transmitting large amounts of file data, which with proper prioritization should enable
 * the application to feel responsive and still accomplish the job of moving music around the network.
 * 
 * This class is meant to be instantiated for each connection, to avoid conflicts with packet number
 * and other state representation.
 * 
 * See MessageFormat and PacketFormat for specific information about those formats.
 * 
 * This protocol supports stacking multiple messages (in that same format) into the same stream.  Each message will
 * have a length that describes that message.
 * 
 * @author Jesse Rosalia
 *
 */
public class Messenger {

    private static final String TAG = Messenger.class.getSimpleName();

    private InputBuffer inputBuffer = new InputBuffer();

    //NOTE: implemented as a map, not a SparseArray, so our unit tests will run
    @SuppressLint("UseSparseArrays")
    private Map<Integer, WireRecvOutputStream> activeTransfers  = new HashMap<Integer, WireRecvOutputStream>();
    private List<IMessage>                     receivedMessages = new LinkedList<IMessage>();
    @SuppressLint("UseSparseArrays")
    private Map<Integer, Long>                 canceledMessages = new HashMap<Integer, Long>();

    /**
     * Maximum size in bytes to read from a socket at a time.
     * 
     */
    private static final int MAX_READ_SIZE_BYTES = 4096;
    private byte[] inBytes = new byte[MAX_READ_SIZE_BYTES];

    private static final int MAX_WRITE_SIZE_BYTES = 4096;

    //default is 1 hour, which should be plenty of time
    private static final int CANCELED_MESSAGES_TTL_MINUTES_DEFAULT = 60;

    private int sendPacketSize;

    private int nextMessageNo = 0;

    private File tempFolder;

    private int canceledMessagesTtlMinutes;
    
    private static final Object activeTransferLock = new Object();

    public Messenger(File tempFolder) {
        this(tempFolder, CANCELED_MESSAGES_TTL_MINUTES_DEFAULT);
    }

    public Messenger(File tempFolder, int canceledMessagesTtlMinutes) {
        this.tempFolder     = tempFolder;
        this.canceledMessagesTtlMinutes = canceledMessagesTtlMinutes;
        this.sendPacketSize = MAX_WRITE_SIZE_BYTES;
    }
    
    /**
     * Serialize a message into the output buffer.  This will append to the output
     * buffer, to stack multiple messages next to each other.  See clearOutputBuffer
     * to clear this buffer.
     * 
     * @param message
     * @throws IOException
     */
    public InputStream serializeMessage(IMessage message) throws IOException {
        MessageFormat format = new MessageFormat(message);
        InputBuffer buffer = new InputBuffer();
        format.serialize(buffer);
        //if this is a file message, open the file and prepare it for the write
        // operation
        InputStream fileStream = null;
        if (isFileMessage(message)) {
            FileReceiver fileFormat = new FileReceiver((IFileMessage) message, this.tempFolder);
            fileStream = fileFormat.getInputStream();
        }
        return new WireSendInputStream(this.sendPacketSize, this.nextMessageNo++, buffer.getInputStream(), fileStream);
    }

    /**
     * @param message
     * @return
     */
    private boolean isFileMessage(IMessage message) {
        return message instanceof IFileMessage;
    }

    /**
     * Deserialize a message in the input stream, and store the result in the receivedMessage field.
     * This may be called multiple times with partial messages (in case the message is not all here yet).
     * 
     * Only one received message may be held at a time, so be prepared to call getReceivedMessage if this
     * method returns true.
     * 
     * This is designed to block until a full message is received, and will throw an exception if the
     * socket is closed unexpectedly.
     * 
     * @param input
     * @return
     * @throws Exception If the message class does not exist, or is not defined properly, or
     * if the stream closes prematurely.
     */
    public boolean deserializeMessage(InputStream input) throws IOException {
        boolean processed = false;
        int read = 0;
        do {
            //always check to see if we have more message data waiting...this is so we can process
            // grouped/batched messages without having to wait on the call to readNext
            if (inputBuffer.size() > 0) {
                //check to see if we can process this message
                if (LogUtil.isLogAvailable()) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Bytes available in " + inputBuffer.size());
                    }
                }
                processed = processAndConsumePackets();
                if (LogUtil.isLogAvailable()) {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Bytes left in " + inputBuffer.size());
                    }
                }
            }
            
            //if we don't have a message processed, attempt to read new data and loop back around
            if (!processed) {
                read = readNext(input);
            }
            //loop back around if we havent processed a message yet
        } while (!processed && read > 0 && inputBuffer.size() > 0);
        return processed;
    }

    /**
     * Process and consume a packet.  This will look for any active message transfers
     * (or create one if needed) and append the bytes to that message.
     * @return 
     * @return
     * @throws IOException 
     */
    private boolean processAndConsumePackets() throws IOException {
        boolean received = false;
        //REVIEW: character encoding issues may arise, but since we're controlling the class names
        // we should be able to decide how to handle these
        PacketFormat packet = new PacketFormat();
        try {
            //while there is data to process, attempt to pull out packets
            //...this will "receive" as many messages as it can, and will throw an exception
            // to exit the loop when we've run out of full packets in our buffer
            while (inputBuffer.size() > 0) {
                packet.deserialize(inputBuffer.getInputStream());
                
                //consume this message in the input buffer
                //NOTE: only happens on successful deserialize (e.g. it doesnt throw a MessageNotCompleteException
                inputBuffer.consume();
    
                //debugging measure...discard future packets for canceled message
                if (shouldDiscardPacket(packet)) {
                    if (LogUtil.isLogAvailable() && Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "Packet for canceled message (number " + packet.getMessageNo() + ") discarded");
                    }
                    continue;
                }

                synchronized (activeTransferLock) {
                    WireRecvOutputStream transfer = this.activeTransfers.get(packet.getMessageNo());
                    if (transfer == null) {
                        transfer = new WireRecvOutputStream(this.tempFolder);
                        this.activeTransfers.put(packet.getMessageNo(), transfer);
                    }
                    //canceled implies the message is totally gone (no chance of restarting)
                    if (packet.isControlCodeSet(ControlCode.Cancelled)) {
                        if (LogUtil.isLogAvailable()) {
                            Log.d(TAG, "Cancellation received for message (number " + packet.getMessageNo() + ")");
                        }
                        cancelMessage(packet.getMessageNo());
                    } else {
                        transfer.write(packet.getBytes());
                        received = transfer.attemptReceive();
                        //if we've received the full message, remove it from our active
                        // transfer array and add the underlying message to the received messages list
                        if (received) {
                            receiveMessage(packet.getMessageNo());
                        }
                    } 
                }
            }
        } catch (MessageNotCompleteException ex) {
            //fall thru
        } finally {
        }
        return received;
    }

    /**
     * Block and read one byte off of the stream.  This is because we NEED to
     * call read to catch any socket disconnect errors (available will simply
     * return 0 for a disconnected socket, which doesn't tell us anything).
     * 
     * Waiting for one byte before filling up inBytes is a happy medium between
     * trying to optimize receive packet size (e.g. inBytes buffer), and passing
     * control back to the message processor as quickly as possible (e.g. reading
     * one byte at a time).  This method, therefore, is best used as a gate to decide
     * if we need to look for more available data.
     * 
     * NOTE we use the array version of read and write.  This is because the single
     * byte version uses -1 (255 unsigned) to indicate that a stream has no more
     * data.  This is really dumb, as 255s show up in our data all the time.  We
     * therefore need to use the array version, with a one byte array, to get accurate
     * data and count of bytes available.
     * 
     * @param input
     * @return
     * @throws IOException
     */
    private int blockAndReadOne(InputStream input) throws IOException {
        byte[] local = new byte[1];
        int read = input.read(local, 0, 1);
        if (read > 0) {
            inputBuffer.write(local, 0, 1);
        }
        return read;
    }
    /**
     * Read the next set of bytes from the input stream.
     * 
     * NOTE: This will block until data is available, and may throw
     * an exception if the stream is closed while reading.
     * 
     * @param input
     * @return 
     * @return 
     * @throws IOException
     */
    private int readNext(InputStream input) throws IOException {
        //read a chunk at a time...the buffer size was determined through trial and error, and
        // could be optimized more.
        //NOTE: this is so input.read can block, and will throw an exception when the connection
        // goes down.  this is the only way we'll get a notification of a downed client
        int totalRead = blockAndReadOne(input);
        int toRead = Math.min(inBytes.length, input.available());
        if (toRead > 0) {
            int read = input.read(inBytes, 0, toRead);
            if (read > 0) {
                inputBuffer.write(inBytes, 0, read);
            }
            totalRead += read;
        }
        return totalRead;
    }

    /**
     * Test if we should discard this packet.  Currently, this occurs only when
     * we've received a packet for a canceled message.
     * 
     * @param packet
     * @return
     */
    private boolean shouldDiscardPacket(PacketFormat packet) {
        return this.canceledMessages.containsKey(packet.getMessageNo());
    }

    private void cancelMessage(int messageNo) {
        this.activeTransfers.remove(messageNo);
        this.canceledMessages.put(messageNo, System.currentTimeMillis());
    }

    /**
     * @param canceledMessagesTtlMinutes
     */
    public void clearExpiredCanceledMessages() {
        double msPerMin = 60000.0;
        @SuppressLint("UseSparseArrays")
        Map<Integer, Long> toKeep = new HashMap<Integer, Long>();
        for (Map.Entry<Integer, Long> entry : this.canceledMessages.entrySet()) {
            if ((System.currentTimeMillis() - entry.getValue()) / msPerMin <= this.canceledMessagesTtlMinutes) {
                toKeep.put(entry.getKey(), entry.getValue());
            }
        }
        this.canceledMessages = toKeep;
    }

    public void clearReceivedMessages() {
        receivedMessages = new LinkedList<IMessage>();
    }
    
    private void receiveMessage(int messageNo) {
        WireRecvOutputStream transfer = this.activeTransfers.remove(messageNo);
        this.receivedMessages.add(transfer.getReceivedMessage());
    }

    /**
     * @return
     */
    public int getActiveTransferCount() {
        return this.activeTransfers.size();
    }

    /**
     * Get the last received message processed by this messenger.
     * 
     * @return
     */
    public List<IMessage> getReceivedMessages() {
        return Collections.unmodifiableList(receivedMessages);
    }

    /**
     * @return
     */
    public int getSendPacketSize() {
        return this.sendPacketSize;
    }

}
