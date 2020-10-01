# ALEXA VOICE LIBRARY

=======

*A library and sample app to abstract access to the Amazon Alexa service for Android applications.*

First and foremost, my goal with this project is to help others who have less of an understanding of Java, Android, or both, to be able to quickly and easily integrate the Amazon Alexa platform into their own applications.

For getting started with the Amazon Alexa platform, take a look over here: https://developer.amazon.com/appsandservices/solutions/alexa/alexa-voice-service/getting-started-with-the-alexa-voice-service

Library updated with functionality for the Alexa [v20160207 API version](https://developer.amazon.com/public/solutions/alexa/alexa-voice-service/content/avs-api-overview).

## Applications

### Sample Application

Curious about what the library can do? A quick example of the three main functions (live recorded audio events, text-to-speech intents, prerecorded audio intents), plus sample code can be found in the [sample app](https://play.google.com/store/apps/details?id=com.willblaschko.android.alexavoicelibrary)

#### Compiling and running the sample application
* Follow the process for creating a connected device detailed at the Amazon link at the top of the Readme.
* Add your api_key.txt file (part of the Amazon process) to the app/src/main/assets folder
* Change [PRODUCT_ID](https://github.com/willblaschko/AlexaAndroid/blob/master/app/src/main/java/com/willblaschko/android/alexavoicelibrary/global/Constants.java#L8) to the value for my Alexa application that you configured for "Application Type Id" above.
* Build and run the sample app using Gradle from the command line or Android Studio!

### Production Application

Or see what the library can do when converted into a full package, complete with optional always-on listener: [Alexa Listens](https://play.google.com/store/apps/details?id=com.willblaschko.android.alexalistens)

## Using the Library

Most of the library can be accessed through the [AlexaManager](http://willblaschko.github.io/AlexaAndroid/com/willblaschko/android/alexa/AlexaManager.html) and [AlexaAudioPlayer](http://willblaschko.github.io/AlexaAndroid/com/willblaschko/android/alexa/avs/AlexaAudioPlayer.html) classes, both of which are singletons.

### Installation

* Ensure you're pulling from jcenter() for your project (project-level build.gradle):
```java
buildscript {
    repositories {
        jcenter()
    }
    ...
}

allprojects {
    repositories {
        jcenter()
    }
}
```
* Add the library to your imports (application-level build.gradle):
```java
compile 'com.willblaschko.android.alexa:AlexaAndroid:2.4.2'
```
* Follow the process for creating a connected device detailed in the Amazon link at the top of the Readme.
* Follow the instructions for adding your key and preparing the Login with Amazon activity from the ['Login with Amazon' Android Project guide](https://developer.amazon.com/public/apis/engage/login-with-amazon/docs/create_android_project.html)
* Add your api_key.txt file (part of the Login with Amazon process detailed in the link above) to the app/src/main/assets folder.
* Start integration and testing!

### Library Instantiation and Basic Return Parsing

```java
private AlexaManager alexaManager;
private AlexaAudioPlayer audioPlayer;
private List<AvsItem> avsQueue = new ArrayList<>();

private void initAlexaAndroid(){
	//get our AlexaManager instance for convenience
	alexaManager = AlexaManager.getInstance(this, PRODUCT_ID);

	//instantiate our audio player
	audioPlayer = AlexaAudioPlayer.getInstance(this);

	//Callback to be able to remove the current item and check queue once we've finished playing an item
	audioPlayer.addCallback(alexaAudioPlayerCallback);
}

//Our callback that deals with removing played items in our media player and then checking to see if more items exist
private AlexaAudioPlayer.Callback alexaAudioPlayerCallback = new AlexaAudioPlayer.Callback() {
	@Override
	public void playerPrepared(AvsItem pendingItem) {

	}

	@Override
	public void itemComplete(AvsItem completedItem) {
		avsQueue.remove(completedItem);
		checkQueue();
	}

	@Override
	public boolean playerError(int what, int extra) {
		return false;
	}

	@Override
	public void dataError(Exception e) {

	}
};

//async callback for commands sent to Alexa Voice
private AsyncCallback<AvsResponse, Exception> requestCallback = new AsyncCallback<AvsResponse, Exception>() {
	@Override
	public void start() {
		//your on start code
	}

	@Override
	public void success(AvsResponse result) {
		Log.i(TAG, "Voice Success");
		handleResponse(result);
	}

	@Override
	public void failure(Exception error) {
		//your on error code
	}

	@Override
	public void complete() {
		 //your on complete code
	}
};

/**
 * Handle the response sent back from Alexa's parsing of the Intent, these can be any of the AvsItem types (play, speak, stop, clear, listen)
 * @param response a List<AvsItem> returned from the mAlexaManager.sendTextRequest() call in sendVoiceToAlexa()
 */
private void handleResponse(AvsResponse response){
	if(response != null){
		//if we have a clear queue item in the list, we need to clear the current queue before proceeding
		//iterate backwards to avoid changing our array positions and getting all the nasty errors that come
		//from doing that
		for(int i = response.size() - 1; i >= 0; i--){
			if(response.get(i) instanceof AvsReplaceAllItem || response.get(i) instanceof AvsReplaceEnqueuedItem){
				//clear our queue
				avsQueue.clear();
				//remove item
				response.remove(i);
			}
		}
		avsQueue.addAll(response);
	}
	checkQueue();
}


/**
 * Check our current queue of items, and if we have more to parse (once we've reached a play or listen callback) then proceed to the
 * next item in our list.
 *
 * We're handling the AvsReplaceAllItem in handleResponse() because it needs to clear everything currently in the queue, before
 * the new items are added to the list, it should have no function here.
 */
private void checkQueue() {

	//if we're out of things, hang up the phone and move on
	if (avsQueue.size() == 0) {
		return;
	}

	AvsItem current = avsQueue.get(0);

	if (current instanceof AvsPlayRemoteItem) {
		//play a URL
		if (!audioPlayer.isPlaying()) {
			audioPlayer.playItem((AvsPlayRemoteItem) current);
		}
	} else if (current instanceof AvsPlayContentItem) {
		//play a URL
		if (!audioPlayer.isPlaying()) {
			audioPlayer.playItem((AvsPlayContentItem) current);
		}
	} else if (current instanceof AvsSpeakItem) {
		//play a sound file
		if (!audioPlayer.isPlaying()) {
			audioPlayer.playItem((AvsSpeakItem) current);
		}
	} else if (current instanceof AvsStopItem) {
		//stop our play
		audioPlayer.stop();
		avsQueue.remove(current);
	} else if (current instanceof AvsReplaceAllItem) {
		audioPlayer.stop();
		avsQueue.remove(current);
	} else if (current instanceof AvsReplaceEnqueuedItem) {
		avsQueue.remove(current);
	} else if (current instanceof AvsExpectSpeechItem) {
		//listen for user input
		audioPlayer.stop();
		startListening();
	} else if (current instanceof AvsSetVolumeItem) {
		setVolume(((AvsSetVolumeItem) current).getVolume());
		avsQueue.remove(current);
	} else if(current instanceof AvsAdjustVolumeItem){
		adjustVolume(((AvsAdjustVolumeItem) current).getAdjustment());
		avsQueue.remove(current);
	} else if(current instanceof AvsSetMuteItem){
		setMute(((AvsSetMuteItem) current).isMute());
		avsQueue.remove(current);
	}else if(current instanceof AvsMediaPlayCommandItem){
		//fake a hardware "play" press
		sendMediaButton(this, KeyEvent.KEYCODE_MEDIA_PLAY);
	}else if(current instanceof AvsMediaPauseCommandItem){
		//fake a hardware "pause" press
		sendMediaButton(this, KeyEvent.KEYCODE_MEDIA_PAUSE);
	}else if(current instanceof AvsMediaNextCommandItem){
		//fake a hardware "next" press
		sendMediaButton(this, KeyEvent.KEYCODE_MEDIA_NEXT);
	}else if(current instanceof AvsMediaPreviousCommandItem){
		//fake a hardware "previous" press
		sendMediaButton(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
	}

}

//our call to start listening when we get a AvsExpectSpeechItem
protected abstract void startListening();

//adjust our device volume
private void adjustVolume(long adjust){
	setVolume(adjust, true);
}

//set our device volume
private void setVolume(long volume){
	setVolume(volume, false);
}

//set our device volume, handles both adjust and set volume to avoid repeating code
private void setVolume(final long volume, final boolean adjust){
	AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
	final int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
	long vol= am.getStreamVolume(AudioManager.STREAM_MUSIC);
	if(adjust){
		vol += volume * max / 100;
	}else{
		vol = volume * max / 100;
	}
	am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) vol, AudioManager.FLAG_VIBRATE);
	//confirm volume change
	alexaManager.sendVolumeChangedEvent(volume, vol == 0, requestCallback);
}

//set device to mute
private void setMute(final boolean isMute){
	AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
	am.setStreamMute(AudioManager.STREAM_MUSIC, isMute);
	//confirm device mute
	alexaManager.sendMutedEvent(isMute, requestCallback);
}

 private static void sendMediaButton(Context context, int keyCode) {
	KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
	Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
	intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
	context.sendOrderedBroadcast(intent, null);

	keyEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
	intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
	intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
	context.sendOrderedBroadcast(intent, null);
}
```


## Recipes

Here's a quick overview of the code that will likely be required to create a solid application, check the [JavaDoc](http://willblaschko.github.io/AlexaAndroid/) for more details.

### Logging In To Amazon
```java
//Run an async check on whether we're logged in or not
alexaManager.checkLoggedIn(mLoggedInCheck);

//Check if the user is already logged in to their Amazon account
alexaManager.checkLoggedIn(AsyncCallback...);

//Log the user in
alexaManager.logIn(AuthorizationCallback...);
```

### Send Live Audio (bonus: check for [Record Audio](https://developer.android.com/reference/android/Manifest.permission.html#RECORD_AUDIO) permission)
```java
private final static int MY_PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
private static final int AUDIO_RATE = 16000;
private RawAudioRecorder recorder;
private RecorderView recorderView;

@Override
public void onResume() {
    super.onResume();

    //request API-23 permission for RECORD_AUDIO
    if (ContextCompat.checkSelfPermission(getActivity(),
            Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
        if (!ActivityCompat.shouldShowRequestPermissionRationale(getActivity(),
                Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(getActivity(),
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MY_PERMISSIONS_REQUEST_RECORD_AUDIO);
        }
    }
}

//recieve RECORD_AUDIO permissions request
@Override
public void onRequestPermissionsResult(int requestCode,
                                       @NonNull String permissions[],
                                       @NonNull int[] grantResults) {
    switch (requestCode) {
        case MY_PERMISSIONS_REQUEST_RECORD_AUDIO: {
            // If request is cancelled, the result arrays are empty.
            if (!(grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED)){
                getActivity().getSupportFragmentManager().beginTransaction().remove(this).commit();
            }
        }

    }
}

@Override
public void onStop() {
    super.onStop();
    //tear down our recorder on stop
    if(recorder != null){
        recorder.stop();
        recorder.release();
        recorder = null;
    }
}

@Override
public void startListening() {
    if(recorder == null){
        recorder = new RawAudioRecorder(AUDIO_RATE);
    }
    recorder.start();
    alexaManager.sendAudioRequest(requestBody, getRequestCallback());
}

//our streaming data requestBody
private DataRequestBody requestBody = new DataRequestBody() {
    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        //while our recorder is not null and it is still recording, keep writing to POST data
        while (recorder != null && !recorder.isPausing()) {
            if(recorder != null) {
                final float rmsdb = recorder.getRmsdb();
                if(recorderView != null) {
                    recorderView.post(new Runnable() {
                        @Override
                        public void run() {
                            recorderView.setRmsdbLevel(rmsdb);
                        }
                    });
                }
                if(sink != null && recorder != null) {
                    sink.write(recorder.consumeRecording());
                }
            }

            //sleep and do it all over again
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        stopListening();
    }

};

//tear down our recorder
private void stopListening(){
    if(recorder != null) {
        recorder.stop();
        recorder.release();
        recorder = null;
    }
}
```

### Send Prerecorded Audio
```java
//send prerecorded audio to Alexa, parse the callback in requestCallback
try {
    //open asset file
    InputStream is = getActivity().getAssets().open("intros/joke.raw");
    byte[] fileBytes=new byte[is.available()];
    is.read(fileBytes);
    is.close();
    mAlexaManager.sendAudioRequest(fileBytes, getRequestCallback());
} catch (IOException e) {
    e.printStackTrace();
}
```

### Send Text Request
```java
//send a text request to Alexa, parse the callback in requestCallback
mAlexaManager.sendTextRequest(text, requestCallback);
```

### Play Returned Content
```java
//Play an AvsPlayItem returned by our requests
audioPlayer.playItem(AvsPlayAudioItem...);

//Play an AvsSpeakItem returned by our requests
audioPlayer.playItem(AvsSpeakItem...);
```

## Everything Else

Let me know if you would like to contribute to this library!

## Thanks
[Josh](https://github.com/joshpar) for MP3 work
