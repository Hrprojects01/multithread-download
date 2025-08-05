package yourpackagename;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipDownloadService extends Service {
	
	private static final String TAG = "ZipDownloadService";
	
	public static final String ACTION_START = "com.my.memory.recall.action.START_DOWNLOAD";
	public static final String ACTION_CANCEL = "com.my.memory.recall.action.CANCEL_DOWNLOAD";
	public static final String EXTRA_URL = "extra_url";
	
	private DownloadListener downloadListener;
	
	private volatile boolean isCanceled = false;
	private Thread downloadThread;
	
	private final int NOTIF_ID = 9876;
	private final String CHANNEL_ID = "download_channel";
	
	private String zipFilePath = null;
	
	private NotificationManager notificationManager;
	
	public interface DownloadListener {
		void onProgress(int percent);
		void onComplete(File extractedDir);
		void onCancel(String reason);
	}
	
	public void setDownloadListener(DownloadListener listener) {
		this.downloadListener = listener;
	}
	
	@Override
	public void onCreate() {
		super.onCreate();
		createNotificationChannel();
		notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
	}
	
	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
			"Download Channel",
			NotificationManager.IMPORTANCE_LOW);
			channel.setDescription("Download progress channel");
			getSystemService(NotificationManager.class).createNotificationChannel(channel);
		}
	}
	
	public static void startDownload(Context context, String url) {
		
		Intent intent = new Intent(context, ZipDownloadService.class);
		intent.setAction(ACTION_START);
		intent.putExtra(EXTRA_URL, url);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.startForegroundService(intent);
		} else {
			context.startService(intent);
		}
	}
	
	public static void cancelDownload(Context context) {
		Intent intent = new Intent(context, ZipDownloadService.class);
		intent.setAction(ACTION_CANCEL);
		context.startService(intent);
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent == null) return START_NOT_STICKY;
		
		String action = intent.getAction();
		Log.d(TAG, "onStartCommand action: " + action);
		
		if (ACTION_START.equals(action)) {
			String url = intent.getStringExtra(EXTRA_URL);
			if (url != null && !url.isEmpty()) {
				startForeground(NOTIF_ID, buildNotification(0, false));
				startDownloadTask(url);
			} else {
				stopSelf();
			}
		} else if (ACTION_CANCEL.equals(action)) {
			Log.d(TAG, "Cancel action received in service");
			cancelProcess("ব্যবহারকারী দ্বারা ডাউনলোড বাতিল করা হয়েছে!");
		}
		return START_STICKY;
	}
	
	
	private Notification buildNotification(int progress, boolean completed) {
		Intent cancelIntent = new Intent(this, ZipDownloadService.class);
		cancelIntent.setAction(ACTION_CANCEL);
		PendingIntent cancelPendingIntent = PendingIntent.getService(this, 0, cancelIntent,
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ?
		PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
		
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
		.setContentTitle("প্রয়োজনীয় ফাইলগুলো ডাউনলোড হচ্ছে")
		.setSmallIcon(android.R.drawable.stat_sys_download)
		.setOnlyAlertOnce(true)
		.setOngoing(!completed)
		.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent);
		
		if (completed) {
			builder.setContentText("ডাউনলোড সম্পন্ন হয়েছে");
			builder.setSmallIcon(android.R.drawable.stat_sys_download_done);
			builder.setProgress(0, 0, false);
		} else {
			builder.setProgress(100, progress, false);
			builder.setContentText(progress + "% ডাউনলোড হয়েছে");
		}
		return builder.build();
	}
	
	private void startDownloadTask(final String urlStr) {
		isCanceled = false;
		
		downloadThread = new Thread(() -> {
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
			
			try {
				File zipFile = new File(getFilesDir(), "temp_download.zip");
				zipFilePath = zipFile.getAbsolutePath();
				
				boolean success = multiThreadDownload(urlStr, zipFile);
				if (!success) {
					postCancel("ডাউনলোড প্রক্রিয়াটি ব্যর্থ হয়েছে!");
					return;
				}
				
				updateNotification(100, true);
				
				if (isCanceled) {
					deleteFileQuiet(zipFile);
					return;
				}
				
				File extractDir = new File(getFilesDir(), "extracted");
				if (extractDir.exists())
				deleteRecursive(extractDir);
				extractDir.mkdirs();
				
				extractZipFile(zipFile, extractDir);
				
				if (isCanceled) {
					deleteRecursive(extractDir);
					deleteFileQuiet(zipFile);
					return;
				}
				
				deleteFileQuiet(zipFile);
				
				postComplete(extractDir);
				
				stopSelf();
				
			} catch (Exception e) {
				Log.e(TAG, "Error during download/extract", e);
				postCancel("Error: " + e.getMessage());
				stopSelf();
			}
		});
		
		downloadThread.start();
	}
	
	private boolean multiThreadDownload(String urlStr, File outputFile) {
		try {
			URL url = new URL(urlStr);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("HEAD");
			int contentLength = conn.getContentLength();
			String acceptRanges = conn.getHeaderField("Accept-Ranges");
			conn.disconnect();
			
			if (contentLength <= 0) {
				return singleThreadDownload(urlStr, outputFile);
			}
			
			if (acceptRanges == null || !acceptRanges.equalsIgnoreCase("bytes")) {
				return singleThreadDownload(urlStr, outputFile);
			}
			
			int parts = 10; // ভাগ সংখ্যা ১০
			
			long partSize = contentLength / parts;
			
			File[] tempFiles = new File[parts];
			Thread[] threads = new Thread[parts];
			
			// আগেকার টেম্প ফাইল ডিলিট করা
			for (int i = 0; i < parts; i++) {
				tempFiles[i] = new File(getFilesDir(), "part" + i + ".tmp");
				deleteFileQuiet(tempFiles[i]);
			}
			
			// থ্রেড তৈরি ও শুরু
			for (int i = 0; i < parts; i++) {
				final int index = i;
				final long start = partSize * i;
				final long end = (i == parts - 1) ? contentLength - 1 : (start + partSize - 1);
				threads[i] = new Thread(() -> rangeDownload(urlStr, start, end, tempFiles[index]));
				threads[i].start();
			}
			
			// থ্রেডগুলো শেষ হওয়া পর্যন্ত ও প্রগ্রেস আপডেট
			boolean anyAlive;
			do {
				if (isCanceled) return false;
				
				anyAlive = false;
				long downloaded = 0;
				for (int i = 0; i < parts; i++) {
					if (threads[i].isAlive()) anyAlive = true;
					if (tempFiles[i].exists()) downloaded += tempFiles[i].length();
				}
				
				int progress = (int) ((downloaded * 100) / contentLength);
				updateNotification(progress, false);
				postProgress(progress);
				
				try {
					Thread.sleep(200);
				} catch (InterruptedException ignored) {
				}
				
			} while (anyAlive);
			
			if (isCanceled) return false;
			
			// সব পার্ট ফাইল এক সাথে মার্জ করা
			try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outputFile))) {
				for (int i = 0; i < parts; i++) {
					try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(tempFiles[i]))) {
						copyStream(bis, bos);
					}
				}
			}
			
			// টেম্প ফাইল ডিলিট করা
			for (File tempFile : tempFiles) {
				deleteFileQuiet(tempFile);
			}
			
			postProgress(100);
			return true;
			
		} catch (Exception e) {
			Log.e(TAG, "Multi-thread download error", e);
			return false;
		}
	}
	
	
	private void rangeDownload(String urlStr, long start, long end, File outputFile) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(urlStr);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
			connection.connect();
			
			if (connection.getResponseCode() / 100 != 2) {
				return;
			}
			
			InputStream input = connection.getInputStream();
			FileOutputStream output = new FileOutputStream(outputFile);
			
			byte[] buffer = new byte[32768];
			int bytesRead;
			while ((bytesRead = input.read(buffer)) != -1) {
				if (isCanceled) {
					output.close();
					input.close();
					return;
				}
				output.write(buffer, 0, bytesRead);
			}
			output.flush();
			output.close();
			input.close();
		} catch (Exception ignored) {
		} finally {
			if (connection != null) connection.disconnect();
		}
	}
	
	private boolean singleThreadDownload(String urlStr, File outputFile) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(urlStr);
			connection = (HttpURLConnection) url.openConnection();
			connection.connect();
			
			if (connection.getResponseCode() / 100 != 2) {
				return false;
			}
			
			int totalSize = connection.getContentLength();
			int downloaded = 0;
			
			InputStream input = connection.getInputStream();
			FileOutputStream output = new FileOutputStream(outputFile);
			
			byte[] buffer = new byte[32768];
			int bytesRead;
			
			while ((bytesRead = input.read(buffer)) != -1) {
				if (isCanceled) {
					output.close();
					input.close();
					return false;
				}
				output.write(buffer, 0, bytesRead);
				downloaded += bytesRead;
				if (totalSize > 0) {
					int progress = (int) ((downloaded * 100L) / totalSize);
					updateNotification(progress, false);
					postProgress(progress);
				}
			}
			
			output.flush();
			output.close();
			input.close();
			postProgress(100);
			return true;
			
		} catch (Exception e) {
			Log.e(TAG, "Single thread download error", e);
			return false;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}
	
	private void extractZipFile(File zipFile, File targetDir) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
			ZipEntry ze;
			byte[] buffer = new byte[32768];
			while ((ze = zis.getNextEntry()) != null) {
				
				if (isCanceled)
				throw new IOException("Canceled");
				
				File outFile = new File(targetDir, ze.getName());
				
				if (ze.isDirectory()) {
					outFile.mkdirs();
				} else {
					File parent = outFile.getParentFile();
					if (parent != null && !parent.exists()) parent.mkdirs();
					
					try (FileOutputStream fos = new FileOutputStream(outFile)) {
						int count;
						while ((count = zis.read(buffer)) != -1) {
							if (isCanceled) throw new IOException("Canceled");
							fos.write(buffer, 0, count);
						}
						fos.flush();
					}
				}
				zis.closeEntry();
			}
		}
	}
	
	private void deleteFileQuiet(File file) {
		try {
			if (file != null && file.exists()) {
				file.delete();
			}
		} catch (Exception ignored) {
		}
	}
	
	private void deleteRecursive(File fileOrDirectory) {
		if (fileOrDirectory.isDirectory()) {
			for (File child : fileOrDirectory.listFiles()) {
				deleteRecursive(child);
			}
		}
		fileOrDirectory.delete();
	}
	
	private void updateNotification(int progress, boolean completed) {
		Notification notification = buildNotification(progress, completed);
		notificationManager.notify(NOTIF_ID, notification);
	}
	
	private void cancelProcess(String reason) {
		isCanceled = true;
		if (downloadThread != null && downloadThread.isAlive()) {
			downloadThread.interrupt();
		}
		updateNotification(0, true);
		postCancel(reason);
		stopSelf();
	}
	
	private void postProgress(int progress) {
		if (downloadListener != null) {
			downloadListener.onProgress(progress);
		}
		Intent intent = new Intent("com.my.memory.recall.DOWNLOAD_PROGRESS");
		intent.putExtra("percent", progress);
		sendBroadcast(intent);
	}
	
	private void postComplete(File extractedDir) {
		if (downloadListener != null) {
			downloadListener.onComplete(extractedDir);
		}
		Intent intent = new Intent("com.my.memory.recall.DOWNLOAD_COMPLETE");
		intent.putExtra("path", extractedDir);
		sendBroadcast(intent);
	}
	
	private void postCancel(String reason) {
		if (downloadListener != null) {
			downloadListener.onCancel(reason);
		}
		Intent intent = new Intent("com.my.memory.recall.DOWNLOAD_CANCEL");
		intent.putExtra("reason", reason);
		sendBroadcast(intent);
	}
	
	private static void copyStream(InputStream is, OutputStream os) throws IOException {
		byte[] buffer = new byte[32768];
		int read;
		while ((read = is.read(buffer)) != -1) {
			os.write(buffer, 0, read);
		}
	}
	
	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
