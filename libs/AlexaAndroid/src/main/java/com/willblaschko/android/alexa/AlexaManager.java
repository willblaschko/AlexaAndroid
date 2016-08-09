package com.willblaschko.android.alexa;

import android.content.Context;
import android.os.AsyncTask;

import com.willblaschko.android.alexa.callbacks.AsyncCallback;
import com.willblaschko.android.alexa.callbacks.AuthorizationCallback;
import com.willblaschko.android.alexa.data.Event;
import com.willblaschko.android.alexa.interfaces.AvsException;
import com.willblaschko.android.alexa.interfaces.AvsItem;
import com.willblaschko.android.alexa.interfaces.AvsResponse;
import com.willblaschko.android.alexa.interfaces.GenericSendEvent;
import com.willblaschko.android.alexa.interfaces.speechrecognizer.SpeechSendAudio;
import com.willblaschko.android.alexa.interfaces.speechrecognizer.SpeechSendText;
import com.willblaschko.android.alexa.interfaces.speechrecognizer.SpeechSendVoice;
import com.willblaschko.android.alexa.interfaces.speechsynthesizer.AvsSpeakItem;
import com.willblaschko.android.alexa.interfaces.system.OpenDownchannel;
import com.willblaschko.android.alexa.requestbody.DataRequestBody;

import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

import okio.BufferedSink;

/**
 * The overarching instance that handles all the state when requesting intents to the Alexa Voice Service servers, it creates all the required instances and confirms that users are logged in
 * and authenticated before allowing them to send intents.
 *
 * Beyond initialization, mostly it supplies wrapped helper functions to the other classes to assure authentication state.
 */
public class AlexaManager {
    private static final String TAG = "AlexaManager";

    private static AlexaManager mInstance;
    private AuthorizationManager mAuthorizationManager;
    private SpeechSendVoice mSpeechSendVoice;
    private SpeechSendText mSpeechSendText;
    private SpeechSendAudio mSpeechSendAudio;
    private VoiceHelper mVoiceHelper;
    private Context mContext;
    private boolean mIsRecording = false;

    private AlexaManager(Context context, String productId){
        mContext = context.getApplicationContext();
        mAuthorizationManager = new AuthorizationManager(mContext, productId);
        mVoiceHelper = VoiceHelper.getInstance(mContext);
    }

    public static AlexaManager getInstance(Context context, String productId){
        if(mInstance == null){
            mInstance = new AlexaManager(context, productId);
        }
        return mInstance;
    }

    public SpeechSendVoice getSpeechSendVoice(){
        if(mSpeechSendVoice == null){
            mSpeechSendVoice = new SpeechSendVoice();
        }
        return mSpeechSendVoice;
    }

    public SpeechSendText getSpeechSendText(){
        if(mSpeechSendText == null){
            mSpeechSendText = new SpeechSendText();
        }
        return mSpeechSendText;
    }

    public SpeechSendAudio getSpeechSendAudio(){
        if(mSpeechSendAudio == null){
            mSpeechSendAudio = new SpeechSendAudio();
        }
        return mSpeechSendAudio;
    }

    public VoiceHelper getVoiceHelper(){
        return mVoiceHelper;
    }

    /**
     * Check if the user is logged in to the Amazon service, uses an async callback with a boolean to return response
     * @param callback state callback
     */
    public void checkLoggedIn(@NotNull final AsyncCallback<Boolean, Throwable> callback){
        mAuthorizationManager.checkLoggedIn(mContext, new AsyncCallback<Boolean, Throwable>() {
            @Override
            public void start() {

            }
            @Override
            public void success(Boolean result) {
                callback.success(result);
            }

            @Override
            public void failure(Throwable error) {
                callback.failure(error);
            }

            @Override
            public void complete() {

            }
        });
    }

    /**
     * Send a log in request to the Amazon Authentication Manager
     * @param callback state callback
     */
    public void logIn(@Nullable final AuthorizationCallback callback){
        //check if we're already logged in
        mAuthorizationManager.checkLoggedIn(mContext, new AsyncCallback<Boolean, Throwable>() {
            @Override
            public void start() {

            }

            @Override
            public void success(Boolean result) {
                //if we are, return a success
                if(result){
                    if(callback != null){
                        callback.onSuccess();
                    }
                }else{
                    //otherwise start the authorization process
                    mAuthorizationManager.authorizeUser(callback);
                }
            }

            @Override
            public void failure(Throwable error) {
                if(callback != null) {
                    callback.onError(new Exception(error));
                }
            }

            @Override
            public void complete() {

            }
        });

    }

    /**
     * Send a get {@link com.willblaschko.android.alexa.data.Directive} request to the Alexa server to open a persistent connection
     */
    public void sendOpenDownchannelDirective(){
        //check if the user is already logged in
        mAuthorizationManager.checkLoggedIn(mContext, new ImplCheckLoggedInCallback() {

            @Override
            public void success(Boolean result) {
                if (result) {
                    //if the user is logged in

                    //set our URL
                    final String url = getDirectivesUrl();
                    //get our access token
                    TokenManager.getAccessToken(mAuthorizationManager.getAmazonAuthorizationManager(), mContext, new TokenManager.TokenCallback() {
                        @Override
                        public void onSuccess(final String token) {
                            //do this off the main thread
                            new AsyncTask<Void, Void, AvsResponse>() {
                                @Override
                                protected AvsResponse doInBackground(Void... params) {
                                    try {
                                        //create a new OpenDownchannel object and send our request
                                        new OpenDownchannel(url, token, new AsyncEventHandler(AlexaManager.this, null));
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                    return null;
                                }
                                @Override
                                protected void onPostExecute(AvsResponse avsResponse) {
                                    super.onPostExecute(avsResponse);
                                }
                            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }

                        @Override
                        public void onFailure(Throwable e) {

                        }
                    });
                } else {
                    //if the user is not logged in, log them in and then call the function again
                    logIn(new ImplAuthorizationCallback<AvsResponse>(null) {
                        @Override
                        public void onSuccess() {
                            //call our function again
                            sendOpenDownchannelDirective();
                        }
                    });
                }
            }

        });
    }

    /**
     * Send a synchronize state {@link Event} request to Alexa Servers to retrieve pending {@link com.willblaschko.android.alexa.data.Directive}
     * See: {@link #sendEvent(String, AsyncCallback)}
     * @param callback state callback
     */
    public void sendSynchronizeStateEvent(@Nullable final AsyncCallback<AvsResponse, Exception> callback){
        sendEvent(Event.getSynchronizeStateEvent(), callback);
    }



    /**
     * Helper function to check if we're currently recording
     * @return
     */
    public boolean isRecording(){
        return mIsRecording;
    }

    /**
     * Helper function to start our recording
     * {@see #startRecording(int, byte[], AsyncCallback)}
     *
     * * @deprecated - Deprecated because of the difficulty of managing Application state in an external library. Avoid using this and {@link #stopRecording(AsyncCallback)},
     * use {@link #sendAudioRequest(byte[], AsyncCallback)} and manage state within your Application/Activity.
     */
    @Deprecated
    public void startRecording(int requestType, @Nullable AsyncCallback<Void, Exception> callback) throws IOException {
        startRecording((byte[]) null, callback);
    }

    /**
     * Helper function to start our recording
     * {@see #startRecording(int, byte[], AsyncCallback)}
     *
     * * @deprecated - Deprecated because of the difficulty of managing Application state in an external library. Avoid using this and {@link #stopRecording(AsyncCallback)},
     * use {@link #sendAudioRequest(byte[], AsyncCallback)} and manage state within your Application/Activity.
     */
    @Deprecated
    public void startRecording(@Nullable AsyncCallback<Void, Exception> callback){
        startRecording((byte[]) null, callback);
    }

    /**
     * Helper function to start our recording
     * {@see #startRecording(int, byte[], AsyncCallback)}
     *
     * * @deprecated - Deprecated because of the difficulty of managing Application state in an external library. Avoid using this and {@link #stopRecording(AsyncCallback)},
     * use {@link #sendAudioRequest(byte[], AsyncCallback)} and manage state within your Application/Activity.
     */
    @Deprecated
    public void startRecording(final int requestType, @Nullable final String assetFile, @Nullable final AsyncCallback<Void, Exception> callback) throws IOException {
        startRecording(assetFile, callback);
    }

    /**
     * Helper function to start our recording
     * {@see #startRecording(int, byte[], AsyncCallback)}
     *
     * * @deprecated - Deprecated because of the difficulty of managing Application state in an external library. Avoid using this and {@link #stopRecording(AsyncCallback)},
     * use {@link #sendAudioRequest(byte[], AsyncCallback)} and manage state within your Application/Activity.
     */
    @Deprecated
    public void startRecording(@Nullable final String assetFile, @Nullable final AsyncCallback<Void, Exception> callback) throws IOException {

        byte[] bytes = null;
        //if we have an introduction audio clip, add it to the stream here
        if(assetFile != null){
            InputStream input= mContext.getAssets().open(assetFile);
            bytes = IOUtils.toByteArray(input);
            input.close();
        }

        startRecording(bytes, callback);
    }

    /**
     * Helper function to start our recording
     * {@see #startRecording(int, byte[], AsyncCallback)}
     *
     * * @deprecated - Deprecated because of the difficulty of managing Application state in an external library. Avoid using this and {@link #stopRecording(AsyncCallback)},
     * use {@link #sendAudioRequest(byte[], AsyncCallback)} and manage state within your Application/Activity.
     */
    @Deprecated
    public void startRecording(final int requestType, @Nullable final byte[] assetFile, @Nullable final AsyncCallback<Void, Exception> callback){
        startRecording(assetFile, callback);
    }

    /**
     * Paired with {@link #stopRecording(AsyncCallback)}--these need to be triggered manually or programmatically as a pair.
     *
     * This operation is done off the main thread and may need to be brought back to the main thread on callbacks.
     *
     * Check to see if the user is logged in, and if not, we request login, when they log in, or if they already are, we start recording audio
     * to pass to the Amazon AVS server. This audio can be pre-pended by the byte[] assetFile, which needs to match the audio requirements of
     * the rest of the service.
     *
     * @deprecated - Deprecated because of the difficulty of managing Application state in an external library. Avoid using this and {@link #stopRecording(AsyncCallback)},
     * use {@link #sendAudioRequest(byte[], AsyncCallback)} and manage state within your Application/Activity.
     *
     * @param assetFile our nullable byte[] that prepends audio to the record request
     * @param callback our state callback
     */
    @Deprecated
    public void startRecording(@Nullable final byte[] assetFile, @Nullable final AsyncCallback<Void, Exception> callback){

        //check if user is logged in
        mAuthorizationManager.checkLoggedIn(mContext, new ImplCheckLoggedInCallback() {

            @Override
            public void success(Boolean result) {
                //if the user is already logged in
                if (result) {

                    final String url = getEventsUrl();
                    if (callback != null) {
                        callback.start();
                    }
                    //perform this off the main thread
                    new AsyncTask<Void, Void, Void>() {
                        @Override
                        protected Void doInBackground(Void... params) {
                            //get our user's access token
                            TokenManager.getAccessToken(mAuthorizationManager.getAmazonAuthorizationManager(), mContext, new TokenManager.TokenCallback() {
                                @Override
                                public void onSuccess(String token) {
                                    //we are authenticated, let's record some audio!
                                    try {
                                        mIsRecording = true;
                                        getSpeechSendVoice().startRecording(url, token, assetFile, callback);

                                        if (callback != null) {
                                            callback.success(null);
                                        }
                                    } catch (IOException e) {
                                        mIsRecording = false;
                                        e.printStackTrace();
                                        //bubble up
                                        if (callback != null) {
                                            callback.failure(e);
                                        }
                                    } finally {
                                        //bubble up
                                        if (callback != null) {
                                            callback.complete();
                                        }
                                    }
                                }

                                @Override
                                public void onFailure(Throwable e) {

                                }
                            });
                            return null;
                        }
                    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                } else {
                    //user is not logged in, log them in
                    logIn(new ImplAuthorizationCallback<Void>(callback) {

                        @Override
                        public void onSuccess() {
                            //start the call all over again
                            startRecording(assetFile, callback);
                        }
                    });
                }
            }
        });
    }

    /**
     * Paired with startRecording()--these need to be triggered manually or programmatically as a pair.
     *
     * This operation is done off the main thread and may need to be brought back to the main thread on callbacks.
     *
     * Stop our current audio being recorded and send the post request off to the server.
     *
     * Warning: this does not check whether we're currently logged in. The world could explode if called without startRecording()
     *
     * @deprecated - Deprecated because of the difficulty of managing Application state in an external library. Avoid using this and {@link #startRecording(AsyncCallback)} (AsyncCallback)},
     * use {@link #sendAudioRequest(byte[], AsyncCallback)} and manage state within your Application/Activity.
     *
     * @param callback
     */
    @Deprecated
    public void stopRecording(@Nullable final AsyncCallback<AvsResponse, Exception> callback){
        if (!mIsRecording) {
            if(callback != null) {
                callback.failure(new RuntimeException("recording not started"));
            }
            return;
        }

        mIsRecording = false;
        if(callback != null) {
            callback.start();
        }

        //make sure we're doing this off the main thread
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    //stop recording audio and get a response
                    AvsResponse response = getSpeechSendVoice().stopRecording();

                    //parse that response
                    try {
                        if (callback != null) {
                            callback.success(response);
                            callback.complete();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (callback != null) {
                            //bubble up the error
                            callback.failure(e);
                            callback.complete();
                        }
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                    //bubble up the error
                    if (callback != null) {
                        callback.failure(e);
                    }
                } catch (AvsException e) {
                    e.printStackTrace();
                    //bubble up the error
                    if (callback != null) {
                        callback.failure(e);
                    }
                } finally {
                    if (callback != null) {
                        callback.complete();
                    }
                }

                return null;
            }

        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

    }

    @Deprecated
    public void sendTextRequest(final int requestType, final String text, @Nullable final AsyncCallback<AvsResponse, Exception> callback){
        sendTextRequest(text, callback);
    }

    /**
     * Send a text string request to the AVS server, this is run through Text-To-Speech to create the raw audio file needed by the AVS server.
     *
     * This allows the developer to pre/post-pend or send any arbitrary text to the server, versus the startRecording()/stopRecording() combination which
     * expects input from the user. This operation, because of the extra steps is generally slower than the above option.
     *
     * @param text the arbitrary text that we want to send to the AVS server
     * @param callback the state change callback
     */
    public void sendTextRequest(final String text, @Nullable final AsyncCallback<AvsResponse, Exception> callback){
        //check if the user is already logged in
        mAuthorizationManager.checkLoggedIn(mContext, new ImplCheckLoggedInCallback() {
            @Override
            public void success(Boolean result) {
                if (result) {
                    //if the user is logged in

                    //set our URL
                    final String url = getEventsUrl();
                    //do this off the main thread
                    new AsyncTask<Void, Void, AvsResponse>() {
                        @Override
                        protected AvsResponse doInBackground(Void... params) {
                            //get our access token
                            TokenManager.getAccessToken(mAuthorizationManager.getAmazonAuthorizationManager(), mContext, new TokenManager.TokenCallback() {
                                @Override
                                public void onSuccess(String token) {

                                    try {
                                        getSpeechSendText().sendText(mContext, url, token, text, new AsyncEventHandler(AlexaManager.this, callback));
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        //bubble up the error
                                        if(callback != null) {
                                            callback.failure(e);
                                        }
                                    }
                                }

                                @Override
                                public void onFailure(Throwable e) {

                                }
                            });
                            return null;
                        }


                        @Override
                        protected void onPostExecute(AvsResponse avsResponse) {
                            super.onPostExecute(avsResponse);
                        }
                    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                } else {
                    //if the user is not logged in, log them in and then call the function again
                    logIn(new ImplAuthorizationCallback<AvsResponse>(callback) {

                        @Override
                        public void onSuccess() {
                            //call our function again
                            sendTextRequest(text, callback);
                        }

                    });
                }
            }

        });
    }

    @Deprecated
    public void sendAudioRequest(final int requestType, final byte[] data, final AsyncCallback<AvsResponse, Exception> callback){
        sendAudioRequest(data, callback);
    }

    /**
     * Send raw audio data to the Alexa servers, this is a more advanced option to bypass other issues (like only one item being able to use the mic at a time).
     *
     * @param data the audio data that we want to send to the AVS server
     * @param callback the state change callback
     */
    public void sendAudioRequest(final byte[] data, @Nullable final AsyncCallback<AvsResponse, Exception> callback){
        sendAudioRequest(new DataRequestBody() {
            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                sink.write(data);
            }
        }, callback);
    }

    /**
     * Send streamed raw audio data to the Alexa servers, this is a more advanced option to bypass other issues (like only one item being able to use the mic at a time).
     *
     * @param requestBody a request body that incorporates either a static byte[] write to the BufferedSink or a streamed, managed byte[] data source
     * @param callback the state change callback
     */
    public void sendAudioRequest(final DataRequestBody requestBody, @Nullable final AsyncCallback<AvsResponse, Exception> callback){
        //check if the user is already logged in
        mAuthorizationManager.checkLoggedIn(mContext, new ImplCheckLoggedInCallback() {

            @Override
            public void success(Boolean result) {
                if (result) {
                    //if the user is logged in

                    //set our URL
                    final String url = getEventsUrl();
                    //get our access token
                    TokenManager.getAccessToken(mAuthorizationManager.getAmazonAuthorizationManager(), mContext, new TokenManager.TokenCallback() {
                        @Override
                        public void onSuccess(final String token) {
                            //do this off the main thread
                            new AsyncTask<Void, Void, AvsResponse>() {
                                @Override
                                protected AvsResponse doInBackground(Void... params) {
                                    try {
                                        getSpeechSendAudio().sendAudio(url, token, requestBody, new AsyncEventHandler(AlexaManager.this, callback));
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                        //bubble up the error
                                        if(callback != null) {
                                            callback.failure(e);
                                        }
                                    }
                                    return null;
                                }
                                @Override
                                protected void onPostExecute(AvsResponse avsResponse) {
                                    super.onPostExecute(avsResponse);
                                }
                            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }

                        @Override
                        public void onFailure(Throwable e) {

                        }
                    });
                } else {
                    //if the user is not logged in, log them in and then call the function again
                    logIn(new ImplAuthorizationCallback<AvsResponse>(callback) {
                        @Override
                        public void onSuccess() {
                            //call our function again
                            sendAudioRequest(requestBody, callback);
                        }
                    });
                }
            }

        });
    }

    /** Send a confirmation to the Alexa server that the device volume has been changed in response to a directive
     * See: {@link #sendEvent(String, AsyncCallback)}
     *
     * @param volume volume as reported by the {@link com.willblaschko.android.alexa.interfaces.speaker.AvsAdjustVolumeItem} Directive
     * @param isMute report whether the device is currently muted
     * @param callback state callback
     */
    public void sendVolumeChangedEvent(final long volume, final boolean isMute, @Nullable final AsyncCallback<AvsResponse, Exception> callback){
        sendEvent(Event.getVolumeChangedEvent(volume, isMute), callback);
    }


    /** Send a confirmation to the Alexa server that the mute state has been changed in response to a directive
     * See: {@link #sendEvent(String, AsyncCallback)}
     *
     * @param isMute mute state as reported by the {@link com.willblaschko.android.alexa.interfaces.speaker.AvsSetMuteItem} Directive
     * @param callback
     */
    public void sendMutedEvent(final boolean isMute, @Nullable final AsyncCallback<AvsResponse, Exception> callback){
        sendEvent(Event.getMuteEvent(isMute), callback);
    }

    /**
     * Send confirmation that the device has timed out without receiving a speech request when expected
     * See: {@link #sendEvent(String, AsyncCallback)}
     *
     * @param callback
     */
    public void sendExpectSpeechTimeoutEvent(final AsyncCallback<AvsResponse, Exception> callback){
        sendEvent(Event.getExpectSpeechTimedOutEvent(), callback);
    }

    /**
     * Send an event to indicate that playback of media has nearly completed
     * See: {@link #sendEvent(String, AsyncCallback)}
     *
     * @param item our playback item
     * @param callback
     */
    public void sendPlaybackNearlyFinishedEvent(AvsItem item, final long offsetMilliseconds, final AsyncCallback<AvsResponse, Exception> callback){
        if (!(item instanceof AvsSpeakItem)) {
            sendEvent(Event.getPlaybackNearlyFinishedEvent(item.getToken(), offsetMilliseconds), callback);
        }
    }


    /**
     * Send an event to indicate that playback of a speech item has started
     * See: {@link #sendEvent(String, AsyncCallback)}
     *
     * @param item our speak item
     * @param callback
     */
    public void sendPlaybackStartedEvent(AvsItem item, final AsyncCallback<AvsResponse, Exception> callback){
        String event;
        if(item instanceof AvsSpeakItem){
            event = Event.getSpeechStartedEvent(item.getToken());
        }else{
            event = Event.getPlaybackStartedEvent(item.getToken());
        }
        sendEvent(event, callback);
    }

    /**
     * Send an event to indicate that playback of a speech item has finished
     * See: {@link #sendEvent(String, AsyncCallback)}
     *
     * @param item our speak item
     * @param callback
     */
    public void sendPlaybackFinishedEvent(AvsItem item, final AsyncCallback<AvsResponse, Exception> callback){
        String event;
        if(item instanceof AvsSpeakItem){
            event = Event.getSpeechFinishedEvent(item.getToken());
        }else{
            event = Event.getPlaybackFinishedEvent(item.getToken());
        }
        sendEvent(event, callback);
    }

    /**
     * Send a generic event to the AVS server, this is generated using {@link com.willblaschko.android.alexa.data.Event.Builder}
     * @param event the string JSON event
     * @param callback
     */
    public void sendEvent(final String event, final AsyncCallback<AvsResponse, Exception> callback){
        //check if the user is already logged in
        mAuthorizationManager.checkLoggedIn(mContext, new ImplCheckLoggedInCallback() {

            @Override
            public void success(Boolean result) {
                if (result) {
                    //if the user is logged in

                    //set our URL
                    final String url = getEventsUrl();
                    //get our access token
                    TokenManager.getAccessToken(mAuthorizationManager.getAmazonAuthorizationManager(), mContext, new TokenManager.TokenCallback() {
                        @Override
                        public void onSuccess(final String token) {
                            //do this off the main thread
                            new AsyncTask<Void, Void, AvsResponse>() {
                                @Override
                                protected AvsResponse doInBackground(Void... params) {
                                    new GenericSendEvent(url, token, event, new AsyncEventHandler(AlexaManager.this, callback));
                                    return null;
                                }
                                @Override
                                protected void onPostExecute(AvsResponse avsResponse) {
                                    super.onPostExecute(avsResponse);
                                }
                            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                        }

                        @Override
                        public void onFailure(Throwable e) {

                        }
                    });
                } else {
                    //if the user is not logged in, log them in and then call the function again
                    logIn(new ImplAuthorizationCallback<AvsResponse>(callback) {
                        @Override
                        public void onSuccess() {
                            //call our function again
                            sendEvent(event, callback);
                        }
                    });
                }
            }

        });
    }

    private String getEventsUrl(){
        return new StringBuilder()
                .append(mContext.getString(R.string.alexa_api))
                .append("/")
                .append(mContext.getString(R.string.alexa_api_version))
                .append("/")
                .append("events")
                .toString();
    }

    private String getDirectivesUrl(){
        return new StringBuilder()
                .append(mContext.getString(R.string.alexa_api))
                .append("/")
                .append(mContext.getString(R.string.alexa_api_version))
                .append("/")
                .append("directives")
                .toString();
    }

    private static class AsyncEventHandler implements AsyncCallback<AvsResponse, Exception>{

        AsyncCallback<AvsResponse, Exception> callback;
        AlexaManager manager;

        public AsyncEventHandler(AlexaManager manager, AsyncCallback<AvsResponse, Exception> callback){
            this.callback = callback;
            this.manager = manager;
        }

        @Override
        public void start() {
            if (callback != null) {
                callback.start();
            }
        }

        @Override
        public void success(AvsResponse result) {
            //parse our response
            if (callback != null) {
                callback.success(result);
            }
        }

        @Override
        public void failure(Exception error) {
            //bubble up the error
            if (callback != null) {
                callback.failure(error);
            }
        }

        @Override
        public void complete() {

            if (callback != null) {
                callback.complete();
            }
            manager.mSpeechSendAudio = null;
            manager.mSpeechSendVoice = null;
            manager.mSpeechSendText = null;
        }
    }

    private abstract static class ImplAuthorizationCallback<E> implements AuthorizationCallback{

        AsyncCallback<E, Exception> callback;

        public ImplAuthorizationCallback(AsyncCallback<E, Exception> callback){
            this.callback = callback;
        }

        @Override
        public void onCancel() {

        }

        @Override
        public void onError(Exception error) {
            if (callback != null) {
                //bubble up the error
                callback.failure(error);
            }
        }
    }

    private abstract static class ImplCheckLoggedInCallback implements AsyncCallback<Boolean, Throwable>{

        @Override
        public void start() {

        }


        @Override
        public void failure(Throwable error) {

        }

        @Override
        public void complete() {

        }
    }
}
