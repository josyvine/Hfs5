package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.InfoHash;  // Updated: Replaces Sha1Hash
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.StateUpdateAlert;  // 2.x: Still StateUpdateAlert (binds to torrent_status_updated_alert)
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.file_storage;
import org.libtorrent4j.swig.libtorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents (Updated: InfoHash)
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    private final Map<InfoHash, String> hashToIdMap; // infoHash -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // 2.x: Hardcoded libtorrent constants for alert.what() values
                // 7 = state_update_alert, 15 = torrent_finished_alert, 13 = torrent_error_alert
                return new int[] { 7, 15, 13 };
            }

            @Override
            public void alert(Alert<?> alert) {
                // 2.x: Use alert.what() for type (int)
                int alertType = alert.what();

                if (alertType == 7) {  // state_update_alert
                    handleStateUpdate((StateUpdateAlert) alert);
                } else if (alertType == 15) {  // torrent_finished_alert
                    handleTorrentFinished((TorrentFinishedAlert) alert);
                } else if (alertType == 13) {  // torrent_error_alert
                    handleTorrentError((TorrentErrorAlert) alert);
                }
            }
        });

        // Start the session, this will start the DHT and other services
        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        // 2.x: alert.status() returns List<TorrentStatus>
        List<TorrentStatus> statuses = alert.status();
        for (TorrentStatus status : statuses) {
            // Updated: status.infoHash() returns InfoHash
            String dropRequestId = hashToIdMap.get(status.infoHash());
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | ↓ " + (status.downloadPayloadRate() / 1024) + " KB/s | ↑ " + (status.uploadPayloadRate() / 1024) + " KB/s");
                // 2.x: total_done() and total_wanted() are long; cast if <2GB
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) status.totalDone());
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, (int) status.totalWanted());
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, status.totalDone());
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        // Updated: handle.infoHash() returns InfoHash
        String dropRequestId = hashToIdMap.get(handle.infoHash());
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        // Updated: handle.infoHash() returns InfoHash
        String dropRequestId = hashToIdMap.get(handle.infoHash());
        // 2.x: alert.error().message() or alert.message()
        String errorMsg = alert.message();
        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);
            
            AddTorrentParams params = new AddTorrentParams();
            // 2.x: Use setters
            params.setTi(torrentInfo);
            params.setSavePath(dataFile.getParentFile().getAbsolutePath());
            
            // 2.x: addTorrent returns TorrentHandle directly
            TorrentHandle handle = sessionManager.addTorrent(params);

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                // Updated: infoHash() returns InfoHash
                hashToIdMap.put(handle.infoHash(), dropRequestId);
                // 2.x: make_magnet_uri()
                String magnetLink = handle.makeMagnetUri();
                Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
                return magnetLink;
            } else {
                Log.e(TAG, "Failed to get valid TorrentHandle after adding seed.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create torrent for seeding: " + e.getMessage(), e);
            return null;
        } finally {
            if (torrentFile != null && torrentFile.exists()) {
                torrentFile.delete();  // Cleanup temp .torrent
            }
        }
    }

    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();
        // 2.x: add_file(String, long)
        fs.add_file(dataFile.getName(), dataFile.length());
        // 2.x: Same
        int pieceSize = (int) libtorrent.default_torrent_piece_size(fs);
        create_torrent ct = new create_torrent(fs, pieceSize);

        // 2.x: Generate hashes first
        ct.generate();
        // Optional: ct.addTracker("udp://tracker.opentrackr.org:1337/announce"); for public tracker

        // 2.x: bencode() after generate()
        byte[] torrentBytes = ct.bencode();

        // Write to temp .torrent file
        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
        }
        return tempTorrent;
    }

    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) {
            saveDirectory.mkdirs();
        }

        try {
            // 2.x: parseMagnetUri(String) static
            AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnetLink);
            params.setSavePath(saveDirectory.getAbsolutePath());
            
            // 2.x: addTorrent returns TorrentHandle
            TorrentHandle handle = sessionManager.addTorrent(params);

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                // Updated: infoHash() returns InfoHash
                hashToIdMap.put(handle.infoHash(), dropRequestId);
                Log.d(TAG, "Started download for request ID: " + dropRequestId);
            } else {
                Log.e(TAG, "Failed to get valid TorrentHandle after adding download from magnet link.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download: " + e.getMessage(), e);
        }
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) {
            return;
        }
        // Updated: infoHash() returns InfoHash
        InfoHash hash = handle.infoHash();
        String dropRequestId = hashToIdMap.get(hash);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(hash);
        }
        // 2.x: remove_torrent(TorrentHandle)
        sessionManager.removeTorrent(handle);
        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null; // Allow re-initialization if needed
    }
}