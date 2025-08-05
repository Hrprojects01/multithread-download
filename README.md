
# multithread-download

A simple example of Android background service for downloading large ZIP files efficiently using multi-threaded HTTP range requests, with real-time progress notifications and automatic ZIP extraction.

---

## What is this project?

`multithread-download` is an Android Service that:

- Downloads large ZIP files from a provided URL.
- Uses multiple threads to download file parts in parallel (if supported by the server) for faster downloads.
- Falls back to single-threaded download if multi-thread is not supported.
- Displays a persistent notification showing download progress and allows user cancellation.
- Extracts the downloaded ZIP file automatically upon completion.
- Sends broadcast intents and callback events for progress, completion, and cancellation.
- Cleans up temporary files and handles cancellation gracefully.

---

## Features and Functionality

- **Multi-threaded download:** Splits file into parts and downloads each part simultaneously for speed.
- **Auto fallback:** Detects if the server supports partial content requests and switches to single-thread if not.
- **Notification with cancel option:** Shows current download percentage and a cancel button in the notification.
- **Graceful cancellation:** User can cancel download anytime, cleaning up all temporary files.
- **ZIP file extraction:** Extracts the ZIP file to an internal app directory after download completes.
- **Broadcast events & listener interface:** Allows other components to listen for progress updates, completion, and cancellation.
- **Background service:** Runs as foreground service with appropriate Android 8.0+ support.

---

## How to Use

1. **Start download:**

Call from any part of your app with a valid URL:

```java
ZipDownloadService.startDownload(context, "https://example.com/path/to/yourfile.zip");
```

2. **Cancel download:**

To cancel an ongoing download:

```java
ZipDownloadService.cancelDownload(context);
```

3. **Listen to events:**

Register a listener to track download progress, completion, or cancellation:

```java
zipDownloadService.setDownloadListener(new ZipDownloadService.DownloadListener() {
    @Override
    public void onProgress(int percent) { /* update UI */ }

    @Override
    public void onComplete(File extractedDir) { /* handle extracted files */ }

    @Override
    public void onCancel(String reason) { /* handle cancel */ }
});
```


#OR
   ```java
   private BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
	@Override
	public void onReceive(Context context, Intent intent) {
		String action = intent.getAction();
		if (action == null) return;
		
		switch (action) {
			case "com.my.memory.recall.DOWNLOAD_PROGRESS":
			int percent = intent.getIntExtra("percent", 0);
			runOnUiThread(() -> {
				
			});
			break;
			
			case "com.my.memory.recall.DOWNLOAD_COMPLETE":
			String dir = intent.getStringExtra("path");
			runOnUiThread(() -> {
				
			});
			break;
			
			case "com.my.memory.recall.DOWNLOAD_CANCEL":
			String reason = intent.getStringExtra("reason");
			runOnUiThread(() -> {
				
			});
			break;
		}};

   ```


4. **Receive broadcasts:**
   

You can also listen for broadcast intents with actions:
- `com.my.memory.recall.DOWNLOAD_PROGRESS` (extra "percent")
- `com.my.memory.recall.DOWNLOAD_COMPLETE` (extra "path")
- `com.my.memory.recall.DOWNLOAD_CANCEL` (extra "reason")

---
5. **Broadcast receiver**
    Register the broadcast receiver
   ```java
   IntentFilter filter = new IntentFilter();
    filter.addAction("com.my.memory.recall.DOWNLOAD_PROGRESS");
    filter.addAction("com.my.memory.recall.DOWNLOAD_COMPLETE");
    filter.addAction("com.my.memory.recall.DOWNLOAD_CANCEL");
    registerReceiver(downloadReceiver, filter);
   ```
   Unregister it
   ```java
   unregisterReceiver(downloadReceiver);
   ```

## Why use this code?

- **Faster downloads:** Multi-threading optimizes file download speeds, especially for large files.
- **User-friendly:** Persistent progress notification with cancel button improves UX.
- **Reliable:** Automatically falls back if the server doesn't support range requests.
- **Convenient:** ZIP extraction is integrated, so no need for separate decompression logic.
- **Flexible:** Broadcasts and listener interface provide multiple ways to integrate with your app.

---

## Requirements

- Android API Level 21+ (Lollipop and above)
- Internet permission in `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

...
			

<service
    android:name=".ZipDownloadService"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
