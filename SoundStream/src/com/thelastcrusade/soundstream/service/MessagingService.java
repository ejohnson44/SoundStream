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

package com.thelastcrusade.soundstream.service;

import java.util.List;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.thelastcrusade.soundstream.model.PlaylistEntry;
import com.thelastcrusade.soundstream.model.SongMetadata;
import com.thelastcrusade.soundstream.model.UserList;
import com.thelastcrusade.soundstream.net.MessageFuture;
import com.thelastcrusade.soundstream.net.MessageThreadMessageDispatch;
import com.thelastcrusade.soundstream.net.MessageThreadMessageDispatch.IMessageHandler;
import com.thelastcrusade.soundstream.net.message.AddToPlaylistMessage;
import com.thelastcrusade.soundstream.net.message.BumpSongOnPlaylistMessage;
import com.thelastcrusade.soundstream.net.message.CancelSongMessage;
import com.thelastcrusade.soundstream.net.message.IMessage;
import com.thelastcrusade.soundstream.net.message.LibraryMessage;
import com.thelastcrusade.soundstream.net.message.PauseMessage;
import com.thelastcrusade.soundstream.net.message.PlayMessage;
import com.thelastcrusade.soundstream.net.message.PlayStatusMessage;
import com.thelastcrusade.soundstream.net.message.PlaylistMessage;
import com.thelastcrusade.soundstream.net.message.RemoveFromPlaylistMessage;
import com.thelastcrusade.soundstream.net.message.RequestSongMessage;
import com.thelastcrusade.soundstream.net.message.SkipMessage;
import com.thelastcrusade.soundstream.net.message.SongStatusMessage;
import com.thelastcrusade.soundstream.net.message.TransferSongMessage;
import com.thelastcrusade.soundstream.net.message.UserListMessage;
import com.thelastcrusade.soundstream.service.ConnectionService.ConnectionServiceBinder;
import com.thelastcrusade.soundstream.util.LocalBroadcastIntent;

public class MessagingService extends Service implements IMessagingService {

    private static final String TAG = MessagingService.class.getSimpleName();

    public static final String ACTION_PAUSE_MESSAGE = MessagingService.class.getName() + ".action.PauseMessage";
    public static final String ACTION_PLAY_MESSAGE  = MessagingService.class.getName() + ".action.PlayMessage";
    public static final String ACTION_SKIP_MESSAGE  = MessagingService.class.getName() + ".action.SkipMessage";
    
    public static final String ACTION_PLAY_STATUS_MESSAGE = MessagingService.class.getName() + ".action.PlayStatusMessage";
    public static final String EXTRA_IS_PLAYING = MessagingService.class.getName() + ".extra.IsPlaying";
    
    public static final String ACTION_LIBRARY_MESSAGE = MessagingService.class.getName() + ".action.LibraryMessage";
    public static final String EXTRA_SONG_METADATA    = MessagingService.class.getName() + ".extra.SongMetadata";

    public static final String ACTION_PLAYLIST_UPDATED_MESSAGE = MessagingService.class.getName() + ".action.PlaylistUpdated";
    public static final String EXTRA_PLAYLIST_ENTRY    = MessagingService.class.getName() + ".extra.PlaylistEntry";
    
    public static final String ACTION_NEW_CONNECTED_USERS_MESSAGE = MessagingService.class.getName() + ".action.UserListMessage";
    public static final String EXTRA_USER_LIST                    = MessagingService.class.getName() + ".extra.UserList";

    public static final String ACTION_REQUEST_SONG_MESSAGE        = MessagingService.class.getName() + ".action.RequestSongMessage";
    public static final String EXTRA_ADDRESS                      = MessagingService.class.getName() + ".extra.Address";
    public static final String EXTRA_SONG_ID                      = MessagingService.class.getName() + ".extra.SongId";

    public static final String ACTION_CANCEL_SONG_MESSAGE         = MessagingService.class.getName() + ".action.CancelSongMessage";
    public static final String ACTION_TRANSFER_SONG_MESSAGE       = MessagingService.class.getName() + ".action.TransferSongMessage";
    //also uses ADDRESS and SONG_ID
    public static final String EXTRA_SONG_FILE_NAME               = MessagingService.class.getName() + ".extra.SongFileName";
    public static final String EXTRA_SONG_TEMP_FILE               = MessagingService.class.getName() + ".extra.SongTempFile";

    public static final String ACTION_ADD_TO_PLAYLIST_MESSAGE      = MessagingService.class.getName() + ".action.AddToPlaylistMessage";
    public static final String ACTION_REMOVE_FROM_PLAYLIST_MESSAGE = MessagingService.class.getName() + ".action.RemoveFromPlaylistMessage";
    public static final String ACTION_BUMP_SONG_ON_PLAYLIST_MESSAGE= MessagingService.class.getName() + ".action.BumpSongOnPlaylistMessage";

    public static final String ACTION_SONG_STATUS_MESSAGE          = MessagingService.class.getName() + ".action.SongStatusMessage";
    public static final String EXTRA_LOADED                        = MessagingService.class.getName() + ".extra.Loaded";
    public static final String EXTRA_PLAYED                        = MessagingService.class.getName() + ".extra.Played";
    public static final String EXTRA_ENTRY_ID                         = MessagingService.class.getName() + ".extra.EntryId";  

    /**
     * A default handler for command messages (messages that do not have any data).  These messages
     * just map to an action.
     * 
     * @author Jesse Rosalia
     *
     * @param <T>
     */
    private class CommandHandler<T extends IMessage> implements IMessageHandler<T> {

        private String action;
        public CommandHandler(String action) {
            this.action = action;
        }

        @Override
        public void handleMessage(int messageNo, T message, String fromAddr) {
            new LocalBroadcastIntent(this.action).send(MessagingService.this);
        }
    }

    /**
     * Class for clients to access.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with
     * IPC.
     */
    public class MessagingServiceBinder extends Binder implements ILocalBinder<MessagingService> {
        public MessagingService getService() {
            return MessagingService.this;
        }
    }

    private MessageThreadMessageDispatch      messageDispatch;
    private ServiceLocator<ConnectionService> connectServiceLocator;

    @Override
    public void onCreate() {
        super.onCreate();
        this.connectServiceLocator = new ServiceLocator<ConnectionService>(
                this, ConnectionService.class, ConnectionServiceBinder.class);
        
        registerMessageHandlers();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new MessagingServiceBinder();
    }
    
    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.connectServiceLocator.unbind();
    }

    public void receiveMessage(int messageNo, IMessage message, String fromAddr) {
        //for ease of implementation, this also uses a message dispatch object.
        this.messageDispatch.handleMessage(messageNo, message, fromAddr);
    }

    private void registerMessageHandlers() {
        this.messageDispatch = new MessageThreadMessageDispatch();
        registerLibraryMessageHandler();
        registerPauseMessageHandler();
        registerPlayMessageHandler();
        registerSkipMessageHandler();
        registerAddToPlaylistMessageHandler();
        registerBumpSongOnPlaylistMessageHandler();
        registerRemoveFromPlaylistMessageHandler();
        registerPlaylistMessageHandler();
        registerPlayStatusMessageHandler();
        registerSongStatusMessageHandler();
        registerCancelSongMessageHandler();
        registerRequestSongMessageHandler();
        registerTransferSongMessageHandler();
        registerUserListMessageHandler();
    }

    private void registerLibraryMessageHandler() {
        this.messageDispatch.registerHandler(LibraryMessage.class, new IMessageHandler<LibraryMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    LibraryMessage message, String fromAddr) {
                new LocalBroadcastIntent(ACTION_LIBRARY_MESSAGE)
                    .putParcelableArrayListExtra(EXTRA_SONG_METADATA, message.getLibrary())
                    .send(MessagingService.this);
            }
        });
    }

    private void registerPauseMessageHandler() {
        this.messageDispatch.registerHandler(PauseMessage.class,
                new CommandHandler<PauseMessage>(ACTION_PAUSE_MESSAGE));
    }
    
    private void registerPlayMessageHandler() {
        this.messageDispatch.registerHandler(PlayMessage.class,
                new CommandHandler<PlayMessage>(ACTION_PLAY_MESSAGE));
    }
    
    private void registerSkipMessageHandler() {
        this.messageDispatch.registerHandler(SkipMessage.class,
                new CommandHandler<SkipMessage>(ACTION_SKIP_MESSAGE));
    }
    
    private void registerPlayStatusMessageHandler() {
        this.messageDispatch.registerHandler(PlayStatusMessage.class,
                new IMessageHandler<PlayStatusMessage>() {

                    @Override
                    public void handleMessage(int messageNo,
                            PlayStatusMessage message, String fromAddr) {
                        new LocalBroadcastIntent(ACTION_PLAY_STATUS_MESSAGE)
                                .putExtra(EXTRA_IS_PLAYING, message.isPlaying())
                                .putExtra(EXTRA_ADDRESS,
                                        message.getMacAddress())
                                .putExtra(EXTRA_SONG_ID, message.getId())
                                .putExtra(EXTRA_ENTRY_ID, message.getEntryId())
                                .send(MessagingService.this);
                    }
                });
    }
    
    private void registerSongStatusMessageHandler() {
        this.messageDispatch.registerHandler(SongStatusMessage.class, new IMessageHandler<SongStatusMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    SongStatusMessage message, String fromAddr) {
                new LocalBroadcastIntent(ACTION_SONG_STATUS_MESSAGE)
                    .putExtra(EXTRA_ADDRESS, message.getMacAddress())
                    .putExtra(EXTRA_SONG_ID, message.getId())
                    .putExtra(EXTRA_ENTRY_ID, message.getEntryId())
                    .putExtra(EXTRA_LOADED,  message.isLoaded())
                    .putExtra(EXTRA_PLAYED,  message.isPlayed())
                    .send(MessagingService.this);
            }
        });
    }

    private void registerCancelSongMessageHandler() {
        this.messageDispatch.registerHandler(CancelSongMessage.class, new IMessageHandler<CancelSongMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    CancelSongMessage message, String fromAddr) {
                new LocalBroadcastIntent(ACTION_CANCEL_SONG_MESSAGE)
                    .putExtra(EXTRA_ADDRESS, fromAddr)
                    .putExtra(EXTRA_SONG_ID, message.getSongId())
                    .send(MessagingService.this);
            }
        });
    }

    private void registerRequestSongMessageHandler() {
        this.messageDispatch.registerHandler(RequestSongMessage.class, new IMessageHandler<RequestSongMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    RequestSongMessage message, String fromAddr) {
                new LocalBroadcastIntent(ACTION_REQUEST_SONG_MESSAGE)
                    .putExtra(EXTRA_ADDRESS, fromAddr)
                    .putExtra(EXTRA_SONG_ID, message.getSongId())
                    .send(MessagingService.this);
            }
        });
    }

    private void registerTransferSongMessageHandler() {
        this.messageDispatch.registerHandler(TransferSongMessage.class, new IMessageHandler<TransferSongMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    TransferSongMessage message, String fromAddr) {
                try {
                    new LocalBroadcastIntent(ACTION_TRANSFER_SONG_MESSAGE)
                        .putExtra(EXTRA_ADDRESS,        fromAddr)
                        .putExtra(EXTRA_SONG_ID,        message.getSongId())
                        .putExtra(EXTRA_SONG_FILE_NAME, message.getSongFileName())
                        .putExtra(EXTRA_SONG_TEMP_FILE, message.getFilePath())
                        .send(MessagingService.this);
                } catch (Exception e) {
                    Log.wtf(TAG, e);
                }

            }
        });
    }

    private void registerUserListMessageHandler(){
        this.messageDispatch.registerHandler(UserListMessage.class, new IMessageHandler<UserListMessage>() {

            @Override
            public void handleMessage(int messageNo, UserListMessage message,
                    String fromAddr) {
                new LocalBroadcastIntent(ACTION_NEW_CONNECTED_USERS_MESSAGE)
                    .putExtra(EXTRA_USER_LIST, message.getUserList())
                    .send(MessagingService.this);
                
            }
        });
    }

    private void registerAddToPlaylistMessageHandler() {
        this.messageDispatch.registerHandler(AddToPlaylistMessage.class,
                new IMessageHandler<AddToPlaylistMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    AddToPlaylistMessage message, String fromAddr) {

                new LocalBroadcastIntent(ACTION_ADD_TO_PLAYLIST_MESSAGE)
                    .putExtra(EXTRA_ADDRESS, message.getMacAddress())
                    .putExtra(EXTRA_SONG_ID, message.getId())
                    .send(MessagingService.this);
            }
        });
    }
    
    private void registerBumpSongOnPlaylistMessageHandler() {
        this.messageDispatch.registerHandler(BumpSongOnPlaylistMessage.class,
                new IMessageHandler<BumpSongOnPlaylistMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    BumpSongOnPlaylistMessage message, String fromAddr) {

                new LocalBroadcastIntent(ACTION_BUMP_SONG_ON_PLAYLIST_MESSAGE)
                    .putExtra(EXTRA_ADDRESS, message.getMacAddress())
                    .putExtra(EXTRA_SONG_ID, message.getId())
                    .putExtra(EXTRA_ENTRY_ID, message.getEntryId())
                    .send(MessagingService.this);
            }
        });
    }

    private void registerRemoveFromPlaylistMessageHandler() {
        this.messageDispatch.registerHandler(RemoveFromPlaylistMessage.class,
                new IMessageHandler<RemoveFromPlaylistMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    RemoveFromPlaylistMessage message, String fromAddr) {

                new LocalBroadcastIntent(ACTION_REMOVE_FROM_PLAYLIST_MESSAGE)
                    .putExtra(EXTRA_ADDRESS, message.getMacAddress())
                    .putExtra(EXTRA_SONG_ID, message.getId())
                    .putExtra(EXTRA_ENTRY_ID, message.getEntryId())
                    .send(MessagingService.this);
            }
        });
    }
    private void registerPlaylistMessageHandler() {
        this.messageDispatch.registerHandler(PlaylistMessage.class,
                new IMessageHandler<PlaylistMessage>() {

            @Override
            public void handleMessage(int messageNo,
                    PlaylistMessage message, String fromAddr) {

                new LocalBroadcastIntent(ACTION_PLAYLIST_UPDATED_MESSAGE)
                    .putParcelableArrayListExtra(EXTRA_PLAYLIST_ENTRY, message.getSongsToPlay())
                    .send(MessagingService.this);
                
                //if we are the host and we are receiving the message as the host, we need to
                //send it back out to all of the guests
                try {
                    if( connectServiceLocator.getService().isGuestConnected()){
                        sendMessageToGuests(message);
                    }
                } catch (ServiceNotBoundException e) {
                    Log.wtf(TAG, e);
                }
            }
        });
    }

    //TODO: support MessageFuture, the next time we need to send a cancelable message to a guest
    private void sendMessageToGuest(String address, IMessage msg) {
        try {
            if (this.connectServiceLocator.getService().isGuestConnected(address)) {
                this.connectServiceLocator.getService().sendMessageToGuest(address, msg);
            }
        } catch (ServiceNotBoundException e) {
            Log.wtf(TAG, e);
        }
    }

    //TODO: support MessageFuture, the next time we need to send a cancelable message to all guests:
    // cancel - should cancel all transmissions
    // isFinished() - should be true once all guests receive the whole message
    private void sendMessageToGuests(IMessage msg) {
        try {
            if (this.connectServiceLocator.getService().isGuestConnected()) {
                this.connectServiceLocator.getService().broadcastMessageToGuests(msg);
            }
        } catch (ServiceNotBoundException e) {
            Log.wtf(TAG, e);
        }
    }

    private MessageFuture sendMessageToHost(IMessage msg) {
        MessageFuture future = null;
        try {
            if (this.connectServiceLocator.getService().isHostConnected()) {
                future = this.connectServiceLocator.getService().sendMessageToHost(msg);
            }
        } catch (ServiceNotBoundException e) {
            Log.wtf(TAG, e);
        }
        return future;
    }

    @Override
    public void sendLibraryMessageToHost(List<SongMetadata> library) {
        LibraryMessage msg = new LibraryMessage(library);
        //send the message to the host
        sendMessageToHost(msg);
    }
    
    @Override
    public void sendLibraryMessageToGuests(List<SongMetadata> library) {
        LibraryMessage msg = new LibraryMessage(library);
        //send the message to the guests
        sendMessageToGuests(msg);
    }

    @Override
    public void sendPauseMessage() {
        PauseMessage msg = new PauseMessage();
        //send the message to the host
        sendMessageToHost(msg);
    }

    @Override
    public void sendPlayMessage() {
        PlayMessage msg = new PlayMessage();
        //send the message to the host
        sendMessageToHost(msg);
    }

    @Override
    public void sendSkipMessage() {
        SkipMessage msg = new SkipMessage();
        //send the message to the host
        sendMessageToHost(msg);
    }

    public void sendPlayStatusMessage(PlaylistEntry currentSong, boolean isPlaying) {
        if (currentSong == null) {
            throw new IllegalArgumentException("currentSong is null");
        }
        PlayStatusMessage msg =
                new PlayStatusMessage(currentSong.getMacAddress(), currentSong.getId(), currentSong.getEntryId(), isPlaying);
    	//send the message to the guests
    	sendMessageToGuests(msg);
    }
    
    @Override
    public void sendAddToPlaylistMessage(PlaylistEntry song) {
        AddToPlaylistMessage msg = new AddToPlaylistMessage(song.getMacAddress(), song.getId(), song.getEntryId());
        //send the message to the host
        sendMessageToHost(msg);
    }
    
    @Override
    public void sendBumpSongOnPlaylistMessage(PlaylistEntry song) {
        BumpSongOnPlaylistMessage msg = new BumpSongOnPlaylistMessage(song.getMacAddress(), song.getId(), song.getEntryId());
        //send the message to the host
        sendMessageToHost(msg);
    }

    @Override
    public void sendRemoveFromPlaylistMessage(PlaylistEntry song) {
        RemoveFromPlaylistMessage msg = new RemoveFromPlaylistMessage(song.getMacAddress(), song.getId(), song.getEntryId());
        //send the message to the host
        sendMessageToHost(msg);
    }
    

    public void sendPlaylistMessage(List<? extends PlaylistEntry> songsToPlay){
        try {
            PlaylistMessage playlistMessage = new PlaylistMessage(songsToPlay);
            //send the message to guests only
            if (this.connectServiceLocator.getService().isGuestConnected()) {
                sendMessageToGuests(playlistMessage);
            }
        } catch (ServiceNotBoundException e) {
            Log.wtf(TAG, e);
        }

    }
    
    @Override
    public void sendSongStatusMessage(PlaylistEntry song) {
        SongStatusMessage msg = new SongStatusMessage(song.getMacAddress(),
                song.getId(), song.getEntryId(), song.isLoaded(), song.isPlayed());
        //send the message to the guests
        sendMessageToGuests(msg);
    }

    @Override
    public void sendCancelSongMessage(String address, long songId) {
        CancelSongMessage msg = new CancelSongMessage(songId);
        //send the message to the guests
        sendMessageToGuest(address, msg);
    }

    @Override
    public void sendRequestSongMessage(String address, long songId) {
        RequestSongMessage msg = new RequestSongMessage(songId);
        //send the message to the guests
        sendMessageToGuest(address, msg);
    }
    
    @Override
    public MessageFuture sendTransferSongMessage(String address, long songId,
            String fileName, String filePath) {
        TransferSongMessage msg = new TransferSongMessage(songId, fileName, filePath);
        //send the message to the fans
        return sendMessageToHost(msg);
    }

    //sends the user list out to everyone
    public void sendUserListMessage(UserList userlist){
        UserListMessage ulm = new UserListMessage(userlist);
        try {
            //send the message to the host
            if (this.connectServiceLocator.getService().isHostConnected()) {
                sendMessageToHost(ulm);
            }

            if (this.connectServiceLocator.getService().isGuestConnected()) {
                sendMessageToGuests(ulm);
            }
        } catch (ServiceNotBoundException e) {
            Log.wtf(TAG, e);
        }
    }
}
