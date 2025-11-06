package com.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final String NOTIFICATION_CHANNEL_ID = "DownloadServiceChannel";
    private static final int NOTIFICATION_ID = 1002;

    private FirebaseFirestore db;
    private ListenerRegistration requestListener;
    private String dropRequestId;
    private File tempCloakedFile;
    private volatile boolean isCancelled = false;
    private Thread downloadThread;

    @Override
    public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            dropRequestId = intent.getStringExtra("drop_request_id");
            final String senderId = intent.getStringExtra("sender_id");
            final String filename = intent.getStringExtra("filename");
            final long filesize = intent.getLongExtra("filesize", 0);

            Notification notification = buildNotification("Starting download...", true, 0, 0);
            startForeground(NOTIFICATION_ID, notification);

            downloadThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    startDownloadProcess(dropRequestId, senderId, filename, filesize);
                }
            });
            downloadThread.start();
        }
        return START_NOT_STICKY;
    }

    private void startDownloadProcess(String docId, String senderId, final String filename, final long totalSize) {
        final DocumentReference docRef = db.collection("drop_requests").document(docId);
        listenForStatusChange(docRef);

        docRef.get().addOnSuccessListener(new OnSuccessListener<DocumentSnapshot>() {
            @Override
            public void onSuccess(DocumentSnapshot documentSnapshot) {
                if (!documentSnapshot.exists()) {
                    stopServiceAndCleanup("Error: Drop request not found.");
                    return;
                }
                String senderIp = documentSnapshot.getString("senderPublicIp");
                long senderPortLong = documentSnapshot.getLong("senderPublicPort");
                String secretNumber = documentSnapshot.getString("secretNumber");

                if (senderIp == null || senderPortLong == 0 || secretNumber == null) {
                    stopServiceAndCleanup("Error: Incomplete connection details.");
                    return;
                }
                int senderPort = (int) senderPortLong;
                downloadFile(senderIp, senderPort, filename, totalSize, secretNumber);
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                stopServiceAndCleanup("Error: Could not retrieve drop details.");
            }
        });
    }

    private void downloadFile(String host, int port, String filename, long totalSize, String secretNumber) {
        long bytesDownloaded = 0;
        Socket socket = null;
        InputStream in = null;
        OutputStream out = null;
        FileOutputStream fos = null;

        try {
            tempCloakedFile = new File(getCacheDir(), "downloading_" + System.currentTimeMillis() + ".log");
            updateNotification("Connecting...", true, 0, (int) totalSize);

            while (bytesDownloaded < totalSize && !isCancelled) {
                try {
                    socket = new Socket(host, port);
                    out = socket.getOutputStream();
                    PrintWriter writer = new PrintWriter(out, true);
                    
                    // Send HTTP GET request with Range header for resuming
                    writer.println("GET / HTTP/1.1");
                    writer.println("Host: " + host);
                    writer.println("Range: bytes=" + bytesDownloaded + "-");
                    writer.println();

                    in = new BufferedInputStream(socket.getInputStream());
                    
                    // Simple HTTP header parsing
                    String line;
                    long contentLength = -1;
                    boolean isChunked = false;
                    
                    // Read headers line by line
                    while ((line = readLine(in)) != null && !line.isEmpty()) {
                        String lowerLine = line.toLowerCase();
                        if (lowerLine.startsWith("content-length:")) {
                            contentLength = Long.parseLong(line.substring(15).trim());
                        }
                    }

                    if (contentLength == -1) {
                         throw new IOException("Server did not provide Content-Length header.");
                    }

                    // Append to file
                    fos = new FileOutputStream(tempCloakedFile, true);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long bytesToRead = contentLength;
                    
                    while (bytesToRead > 0 && (bytesRead = in.read(buffer, 0, (int) Math.min(buffer.length, bytesToRead))) != -1) {
                        if (isCancelled) break;
                        fos.write(buffer, 0, bytesRead);
                        bytesDownloaded += bytesRead;
                        bytesToRead -= bytesRead;
                        updateNotification("Text Cloaking...", true, (int) bytesDownloaded, (int) totalSize);
                    }

                } catch (IOException e) {
                    Log.w(TAG, "Connection lost, will retry... " + e.getMessage());
                    // Clean up for this attempt
                    try { if (socket != null) socket.close(); } catch (IOException ignored) {}
                    try { if (fos != null) fos.close(); } catch (IOException ignored) {}
                    // Wait before retrying
                    Thread.sleep(5000); 
                }
            }

            if (isCancelled) {
                 stopServiceAndCleanup("Download cancelled.");
                 return;
            }

            if (bytesDownloaded == totalSize) {
                updateNotification("Restoring file...", true, (int) totalSize, (int) totalSize);
                File publicDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HFM Drop");
                if (!publicDir.exists()) {
                    publicDir.mkdirs();
                }
                File finalFile = new File(publicDir, filename);

                boolean success = CloakingManager.restoreFile(tempCloakedFile, finalFile, secretNumber);
                if (success) {
                    db.collection("drop_requests").document(dropRequestId).update("status", "complete");
                    updateNotification("Download Complete", false, 100, 100);
                    scanFile(finalFile);
                    if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                        FirebaseAuth.getInstance().getCurrentUser().delete();
                    }
                } else {
                    db.collection("drop_requests").document(dropRequestId).update("status", "error");
                    stopServiceAndCleanup("Error: File decryption failed.");
                }
            } else {
                 stopServiceAndCleanup("Error: Download was incomplete.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Download process failed.", e);
            stopServiceAndCleanup("Error: " + e.getMessage());
        } finally {
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            try { if (fos != null) fos.close(); } catch (IOException ignored) {}
            if(!isCancelled){
                stopSelf();
            }
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            bos.write(c);
        }
        if(c == -1 && bos.size() == 0){
            return null;
        }
        return bos.toString("UTF-8").trim();
    }


    private void listenForStatusChange(DocumentReference docRef) {
        requestListener = docRef.addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(DocumentSnapshot snapshot, FirebaseFirestoreException e) {
                if (e != null) {
                    return;
                }
                if (snapshot != null && snapshot.exists()) {
                    String status = snapshot.getString("status");
                    if ("error".equals(status) || "declined".equals(status)) {
                        isCancelled = true;
                        if (downloadThread != null) {
                            downloadThread.interrupt();
                        }
                    }
                } else {
                    // Document deleted by sender or cleanup function
                    isCancelled = true;
                    if (downloadThread != null) {
                        downloadThread.interrupt();
                    }
                }
            }
        });
    }

    private void stopServiceAndCleanup(final String toastMessage) {
        if (toastMessage != null) {
             new Handler(getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(DownloadService.this, toastMessage, Toast.LENGTH_LONG).show();
                }
            });
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "DownloadService onDestroy.");
        isCancelled = true;
        if (downloadThread != null) {
            downloadThread.interrupt();
        }
        if (requestListener != null) {
            requestListener.remove();
        }
        if (tempCloakedFile != null && tempCloakedFile.exists()) {
            tempCloakedFile.delete();
        }
        stopForeground(true);
    }
    
    private void scanFile(File file) {
        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(Uri.fromFile(file));
        sendBroadcast(mediaScanIntent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "HFM Drop Downloader",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, boolean ongoing, int progress, int max) {
        Notification notification = buildNotification(text, ongoing, progress, max);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text, boolean ongoing, int progress, int max) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("HFM Drop")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);
        if (max > 0) {
            builder.setProgress(max, progress, false);
        } else {
            builder.setProgress(0, 0, true); // Indeterminate
        }
        return builder.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}