package org.renpy.android.cloudsave;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONArray;

public class DownloadSaveActivity extends Activity {
    private LinearLayout gameListLayout;
    private LinearLayout folderListLayout;
    private LinearLayout gamesContainer;
    private LinearLayout foldersContainer;
    private List<String> installedGames = new ArrayList<>();
    private List<String> serverGames = new ArrayList<>();
    private List<String> uploadedFolders = new ArrayList<>();
    private List<Float> folderProgress = new ArrayList<>();
    private List<Boolean> folderTop10Status = new ArrayList<>();
    private List<Boolean> folderSharedStatus = new ArrayList<>();
    private List<Integer> folderDaysRemaining = new ArrayList<>();
    private List<Boolean> folderExpiredStatus = new ArrayList<>();
    public String pendingRefreshTimestamp = null;
    private String highlightGameId = null;
    private String highlightFolderTimestamp = null;
    private List<CommunitySave> communityTopProgress = new ArrayList<>();
    private List<CommunitySave> communityTopLiked = new ArrayList<>();
    private RenPyProgressTracker progressTracker;
    private String currentUserId;
    private String selectedGame;
    private boolean showingFolders = false;
    
    // Caching for community saves
    private String cachedGameId = "";
    private long lastCommunityUpdate = 0;
    private static final long COMMUNITY_CACHE_DURATION = 180000; // 3 minutes cache
    
    // Progress overlay components
    private LinearLayout progressOverlay;
    private TextView progressTitle;
    private TextView progressText;
    private ProgressBar progressBar;
    private TextView progressPercentage;
    
    // Community save data class
    private static class CommunitySave {
        String saveId;
        String userId;
        String folderTimestamp;
        float progress;
        String playerName;
        String profileImage;
        int likes;
        int dislikes;
        int downloads;
        String patreonLink;
        String buymeacoffeeLink;
        boolean isPremium;
        java.util.List<Comment> recentComments;
        
        static class Comment {
            String playerName;
            String comment;
            boolean isLike;
            String date;
            String profileImage;
            boolean isPremium;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize progress tracker
        progressTracker = RenPyProgressTracker.getInstance();
        
        // Set current user ID to match backend format
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("cloudsave", MODE_PRIVATE);
            String userEmail = prefs.getString("user_email", "");
            if (!userEmail.isEmpty()) {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
                byte[] hash = md.digest(userEmail.getBytes());
                StringBuilder hexString = new StringBuilder();
                for (int i = 0; i < Math.min(4, hash.length); i++) {
                    String hex = Integer.toHexString(0xff & hash[i]);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                currentUserId = hexString.toString();
            }
        } catch (Exception e) {}
        
        // Get notification highlight parameters
        highlightGameId = getIntent().getStringExtra("highlight_game_id");
        highlightFolderTimestamp = getIntent().getStringExtra("highlight_folder_timestamp");
        
        // Check for pending rating first
        checkForPendingRating();
        
        createLayout();
        scanForGames();
        
        // Handle notification highlights
        if (highlightGameId != null) {
            // Auto-select the game from notification
            new android.os.Handler().postDelayed(() -> {
                for (String game : installedGames) {
                    if (game.contains(highlightGameId)) {
                        selectGame(game);
                        break;
                    }
                }
            }, 1000);
        }
        
        // Initialize Unity Ads
        UnityAdsHelper.initializeAds(this, new com.unity3d.ads.IUnityAdsInitializationListener() {
            @Override
            public void onInitializationComplete() {
                UnityAdsHelper.onInitializationComplete();
            }
            
            @Override
            public void onInitializationFailed(com.unity3d.ads.UnityAds.UnityAdsInitializationError error, String message) {
                UnityAdsHelper.onInitializationFailed(error, message);
            }
        });
    }
    
    private void checkForPendingRating() {
        android.content.SharedPreferences prefs = getSharedPreferences("cloudsave", MODE_PRIVATE);
        String lastDownloadedSaveId = prefs.getString("last_downloaded_save_id", "");
        
        if (!lastDownloadedSaveId.isEmpty()) {
            String playerName = prefs.getString("last_downloaded_player", "Unknown");
            float progress = prefs.getFloat("last_downloaded_progress", 0.0f);
            showRatingDialog(lastDownloadedSaveId, playerName, progress);
        }
    }
    
    private void createLayout() {
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setFillViewport(false);
        
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(0xFFFFFFFF);
        rootLayout.setPadding(0, 0, 0, 100);
        
        LinearLayout headerContainer = new LinearLayout(this);
        headerContainer.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable headerBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFF1B365D, 0xFF6C757D});
        headerContainer.setBackground(headerBg);
        headerContainer.setPadding(20, 28, 20, 28);
        
        TextView headerText = new TextView(this);
        headerText.setText("⚡ ANDROID PORTER");
        headerText.setTextSize(28);
        headerText.setTextColor(0xFFFFFFFF);
        headerText.setGravity(android.view.Gravity.CENTER);
        headerText.setTypeface(null, android.graphics.Typeface.BOLD);
        headerText.setLetterSpacing(0.15f);
        headerContainer.addView(headerText);
        
        TextView subHeader = new TextView(this);
        subHeader.setText("Download Saves");
        subHeader.setTextSize(14);
        subHeader.setTextColor(0xFFF8F9FA);
        subHeader.setGravity(android.view.Gravity.CENTER);
        subHeader.setPadding(0, 6, 0, 0);
        subHeader.setLetterSpacing(0.1f);
        headerContainer.addView(subHeader);
        
        rootLayout.addView(headerContainer);
        
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(24, 24, 24, 24);
        android.graphics.drawable.GradientDrawable contentBg = new android.graphics.drawable.GradientDrawable();
        contentBg.setColor(0xFFFFFFFF);
        contentBg.setCornerRadius(16);
        contentBg.setStroke(1, 0xFFE0E0E0);
        contentLayout.setBackground(contentBg);
        contentLayout.setElevation(8);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentParams.setMargins(16, 16, 16, 80);
        contentLayout.setLayoutParams(contentParams);
        
        // Title with icon
        // Title container with My Activity button
        LinearLayout titleContainer = new LinearLayout(this);
        titleContainer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams titleContainerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleContainerParams.setMargins(0, 0, 0, 20);
        titleContainer.setLayoutParams(titleContainerParams);
        
        TextView title = new TextView(this);
        title.setText("📥 Select Game to Download To");
        title.setTextSize(20);
        title.setTextColor(0xFF1B365D);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.weight = 1;
        title.setLayoutParams(titleParams);
        titleContainer.addView(title);
        
        Button activityBtn = new Button(this);
        activityBtn.setText("📊 My Activity");
        activityBtn.setTextSize(12);
        activityBtn.setTextColor(0xFFFFFFFF);
        activityBtn.setBackgroundColor(0xFF6C757D);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(0xFF6C757D);
        btnBg.setCornerRadius(16);
        activityBtn.setBackground(btnBg);
        activityBtn.setOnClickListener(v -> openMyActivity());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, 120);
        activityBtn.setLayoutParams(btnParams);
        titleContainer.addView(activityBtn);
        
        contentLayout.addView(titleContainer);
        
        // Info card
        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable infoBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF1B365D, 0xFF6C757D});
        infoBg.setCornerRadius(12);
        infoCard.setBackground(infoBg);
        infoCard.setElevation(4);
        infoCard.setPadding(16, 16, 16, 16);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 16);
        infoCard.setLayoutParams(cardParams);
        
        TextView infoLabel = new TextView(this);
        infoLabel.setText("Instructions:");
        infoLabel.setTextSize(12);
        infoLabel.setTextColor(0xFFF8F9FA);
        infoLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        infoCard.addView(infoLabel);
        
        TextView infoText = new TextView(this);
        infoText.setText("Select a game to download save files from the cloud");
        infoText.setTextSize(14);
        infoText.setTextColor(0xFFFFFFFF);
        infoText.setPadding(0, 4, 0, 0);
        infoCard.addView(infoText);
        
        contentLayout.addView(infoCard);
        
        // Games list container
        LinearLayout gamesContainer = new LinearLayout(this);
        gamesContainer.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable gamesBg = new android.graphics.drawable.GradientDrawable();
        gamesBg.setColor(0xFFF8F9FA);
        gamesBg.setCornerRadius(12);
        gamesBg.setStroke(1, 0xFFE0E0E0);
        gamesContainer.setBackground(gamesBg);
        gamesContainer.setElevation(4);
        gamesContainer.setPadding(16, 16, 16, 16);
        gamesContainer.setLayoutParams(cardParams);
        
        TextView gamesLabel = new TextView(this);
        gamesLabel.setText("Available Games:");
        gamesLabel.setTextSize(14);
        gamesLabel.setTextColor(0xFF1B365D);
        gamesLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        gamesContainer.addView(gamesLabel);
        
        gameListLayout = new LinearLayout(this);
        gameListLayout.setOrientation(LinearLayout.VERTICAL);
        gameListLayout.setPadding(0, 12, 0, 0);
        gamesContainer.addView(gameListLayout);
        
        this.gamesContainer = gamesContainer;
        contentLayout.addView(gamesContainer);
        
        // Folders list container (initially hidden)
        foldersContainer = new LinearLayout(this);
        foldersContainer.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable foldersBg = new android.graphics.drawable.GradientDrawable();
        foldersBg.setColor(0xFFF8F9FA);
        foldersBg.setCornerRadius(12);
        foldersBg.setStroke(1, 0xFFE0E0E0);
        foldersContainer.setBackground(foldersBg);
        foldersContainer.setElevation(4);
        foldersContainer.setPadding(16, 16, 16, 16);
        foldersContainer.setLayoutParams(cardParams);
        foldersContainer.setVisibility(LinearLayout.GONE);
        
        TextView foldersLabel = new TextView(this);
        foldersLabel.setText("Available Backups:");
        foldersLabel.setTextSize(14);
        foldersLabel.setTextColor(0xFF1B365D);
        foldersLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        foldersContainer.addView(foldersLabel);
        
        folderListLayout = new LinearLayout(this);
        folderListLayout.setOrientation(LinearLayout.VERTICAL);
        folderListLayout.setPadding(0, 12, 0, 0);
        foldersContainer.addView(folderListLayout);
        
        this.foldersContainer = foldersContainer;
        contentLayout.addView(foldersContainer);
        
        // Back button with gradient
        Button backButton = new Button(this);
        backButton.setText("Back");
        backButton.setTextColor(0xFFFFFFFF);
        backButton.setTextSize(16);
        backButton.setTypeface(null, android.graphics.Typeface.BOLD);
        backButton.setSingleLine(false);
        android.graphics.drawable.GradientDrawable backBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFF6C757D, 0xFF1B365D});
        backBg.setCornerRadius(12);
        backButton.setBackground(backBg);
        backButton.setElevation(6);
        backButton.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 100);
        backParams.setMargins(0, 20, 0, 20);
        backButton.setLayoutParams(backParams);
        backButton.setPadding(20, 20, 20, 20);
        contentLayout.addView(backButton);
        
        rootLayout.addView(contentLayout);
        
        LinearLayout footerContainer = new LinearLayout(this);
        footerContainer.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable footerBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFF1B365D, 0xFF6C757D});
        footerContainer.setBackground(footerBg);
        footerContainer.setPadding(20, 20, 20, 24);
        
        TextView footerText = new TextView(this);
        footerText.setText("✨ Powered by Bros Ports ✨");
        footerText.setTextSize(13);
        footerText.setTextColor(0xFFFFFFFF);
        footerText.setGravity(android.view.Gravity.CENTER);
        footerText.setTypeface(null, android.graphics.Typeface.BOLD);
        footerText.setLetterSpacing(0.1f);
        footerContainer.addView(footerText);
        
        rootLayout.addView(footerContainer);
        
        scrollView.addView(rootLayout);
        setContentView(scrollView);
    }
    
    private void scanForGames() {
        Toast.makeText(this, "Scanning for current game...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                
                // Get current app's package name
                String currentPackage = getPackageName();
                android.util.Log.d("CloudSave", "DEBUG: Current package: " + currentPackage);
                
                installedGames.clear();
                
                try {
                    ApplicationInfo app = pm.getApplicationInfo(currentPackage, 0);
                    String appName = pm.getApplicationLabel(app).toString();
                    String gameEntry = appName + " (" + currentPackage + ")";
                    installedGames.add(gameEntry);
                    android.util.Log.d("CloudSave", "DEBUG: Added current game: " + gameEntry);
                } catch (Exception e) {
                    android.util.Log.d("CloudSave", "DEBUG: Error getting current app info: " + e.getMessage());
                }
                
                android.util.Log.d("CloudSave", "DEBUG: Found " + installedGames.size() + " Ren'Py games");
                
                runOnUiThread(() -> {
                    if (installedGames.isEmpty()) {
                        LinearLayout emptyCard = new LinearLayout(this);
                        emptyCard.setOrientation(LinearLayout.VERTICAL);
                        android.graphics.drawable.GradientDrawable emptyBg = new android.graphics.drawable.GradientDrawable();
                        emptyBg.setColor(0xFFFFFFFF);
                        emptyBg.setCornerRadius(8);
                        emptyBg.setStroke(1, 0xFFE0E0E0);
                        emptyCard.setBackground(emptyBg);
                        emptyCard.setPadding(20, 20, 20, 20);
                        emptyCard.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                        
                        TextView noGames = new TextView(this);
                        noGames.setText("😔 No Ren'Py games found\n\nPlease install some Ren'Py games first");
                        noGames.setTextColor(0xFF1B365D);
                        noGames.setTextSize(14);
                        noGames.setGravity(android.view.Gravity.CENTER);
                        emptyCard.addView(noGames);
                        
                        gameListLayout.addView(emptyCard);
                    } else {
                        Toast.makeText(this, "Found " + installedGames.size() + " Ren'Py games", Toast.LENGTH_SHORT).show();
                        displayGames();
                        loadServerGames();
                    }
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error scanning games: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private void displayGames() {
        // Current game section
        for (String game : installedGames) {
            LinearLayout gameCard = createGameCard(game, "🎮", 0xFF1B365D);
            gameCard.setOnClickListener(v -> selectGame(game));
            gameListLayout.addView(gameCard);
        }
    }
    
    private void loadServerGames() {
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/games?" +
                    "package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8");
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    org.json.JSONObject jsonObj = new org.json.JSONObject(jsonResponse);
                    org.json.JSONArray gamesArray = jsonObj.optJSONArray("games");
                    
                    serverGames.clear();
                    if (gamesArray != null) {
                        for (int i = 0; i < gamesArray.length(); i++) {
                            org.json.JSONObject gameObj = gamesArray.getJSONObject(i);
                            String gameId = gameObj.optString("game_id", "");
                            String title = gameObj.optString("title", "");
                            int saveCount = gameObj.optInt("save_count", 0);
                            
                            if (!gameId.equals(getPackageName())) {
                                serverGames.add(title + " (" + gameId + ") - " + saveCount + " backups");
                            }
                        }
                    }
                    
                    runOnUiThread(() -> displayServerGames());
                }
            } catch (Exception e) {
                android.util.Log.e("CloudSave", "Error loading server games: " + e.getMessage());
            }
        }).start();
    }
    
    private void displayServerGames() {
        // Add spacer
        android.view.View spacer = new android.view.View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 24);
        spacer.setLayoutParams(spacerParams);
        gameListLayout.addView(spacer);
        
        // Server games header
        TextView serverHeader = new TextView(this);
        serverHeader.setText("🌐 SERVER BACKUPS");
        serverHeader.setTextSize(16);
        serverHeader.setTextColor(0xFF1B365D);
        serverHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        serverHeader.setPadding(0, 0, 0, 12);
        gameListLayout.addView(serverHeader);
        
        if (serverGames.isEmpty()) {
            // Empty server games card
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable emptyBg = new android.graphics.drawable.GradientDrawable();
            emptyBg.setColor(0xFFFFFFFF);
            emptyBg.setCornerRadius(8);
            emptyBg.setStroke(1, 0xFFE0E0E0);
            emptyCard.setBackground(emptyBg);
            emptyCard.setPadding(20, 20, 20, 20);
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            emptyParams.setMargins(0, 0, 0, 8);
            emptyCard.setLayoutParams(emptyParams);
            
            TextView emptyText = new TextView(this);
            emptyText.setText("😔 No other games found on server\n\nBe the first to upload saves for other games!");
            emptyText.setTextColor(0xFF6C757D);
            emptyText.setTextSize(14);
            emptyText.setGravity(android.view.Gravity.CENTER);
            emptyCard.addView(emptyText);
            
            gameListLayout.addView(emptyCard);
        } else {
            // Server game cards
            for (String game : serverGames) {
                LinearLayout gameCard = createGameCard(game, "🌐", 0xFF6C757D);
                gameCard.setOnClickListener(v -> selectGame(game));
                gameListLayout.addView(gameCard);
            }
        }
    }
    
    private LinearLayout createGameCard(String game, String icon, int borderColor) {
        LinearLayout gameCard = new LinearLayout(this);
        gameCard.setOrientation(LinearLayout.HORIZONTAL);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(8);
        cardBg.setStroke(1, borderColor);
        gameCard.setBackground(cardBg);
        gameCard.setElevation(2);
        gameCard.setPadding(16, 12, 16, 12);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 8);
        gameCard.setLayoutParams(cardParams);
        
        TextView gameIcon = new TextView(this);
        gameIcon.setText(icon);
        gameIcon.setTextSize(20);
        gameIcon.setPadding(0, 0, 12, 0);
        gameCard.addView(gameIcon);
        
        TextView gameText = new TextView(this);
        gameText.setText(game);
        gameText.setTextColor(0xFF1B365D);
        gameText.setTextSize(14);
        gameText.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        gameText.setLayoutParams(textParams);
        gameCard.addView(gameText);
        
        TextView arrow = new TextView(this);
        arrow.setText("▶");
        arrow.setTextColor(0xFF1B365D);
        arrow.setTextSize(16);
        gameCard.addView(arrow);
        
        return gameCard;
    }
    
    private void selectGame(String game) {
        this.selectedGame = game;
        showingFolders = true;
        
        // Clear cache when switching games
        if (!game.equals(cachedGameId)) {
            lastCommunityUpdate = 0;
            cachedGameId = "";
        }
        
        // Hide games container and show folders container
        gamesContainer.setVisibility(LinearLayout.GONE);
        foldersContainer.setVisibility(LinearLayout.VISIBLE);
        
        // Update title and add activity button
        LinearLayout contentLayout = (LinearLayout) gamesContainer.getParent();
        LinearLayout titleContainer = (LinearLayout) contentLayout.getChildAt(0);
        TextView title = (TextView) titleContainer.getChildAt(0);
        title.setText("📥 Select Backup to Download");
        
        // Add My Activity button if not already added
        if (titleContainer.getChildCount() == 1) {
            Button activityBtn = new Button(this);
            activityBtn.setText("📊 My Activity");
            activityBtn.setTextSize(12);
            activityBtn.setTextColor(0xFFFFFFFF);
            activityBtn.setBackgroundColor(0xFF6C757D);
            android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
            btnBg.setColor(0xFF6C757D);
            btnBg.setCornerRadius(16);
            activityBtn.setBackground(btnBg);
            activityBtn.setOnClickListener(v -> openMyActivity());
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 120);
            btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 120);
            btnParams.gravity = android.view.Gravity.END;
            activityBtn.setLayoutParams(btnParams);
            titleContainer.addView(activityBtn);
        }
        
        // Load folders for selected game
        loadUploadedFolders();
    }
    
    private void loadUploadedFolders() {
        // Clear cache when explicitly refreshing
        lastCommunityUpdate = 0;
        cachedGameId = "";
        
        Toast.makeText(this, "Loading saves...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gameId = extractPackageName(selectedGame);
                
                // Load user backups
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/list?" +
                    "game_id=" + URLEncoder.encode(gameId, "UTF-8") +
                    "&package_name=" + URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + URLEncoder.encode(deviceInfo, "UTF-8");
                
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                uploadedFolders.clear();
                folderProgress.clear();
                folderTop10Status.clear();
                folderSharedStatus.clear();
                
                String jsonResponse = "";
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    jsonResponse = response.toString();
                    android.util.Log.d("CloudSave", "API Response: " + jsonResponse);
                    
                    if (jsonResponse.contains("\"backups\":")) {
                        String[] parts = jsonResponse.split("\"folder_name\":\"");
                        android.util.Log.d("CloudSave", "Found " + (parts.length - 1) + " backup entries");
                        
                        for (int i = 1; i < parts.length; i++) {
                            String folderPart = parts[i];
                            int end = folderPart.indexOf("\"");
                            if (end > 0) {
                                String folderName = folderPart.substring(0, end);
                                android.util.Log.d("CloudSave", "Processing folder: " + folderName);
                                
                                float progress = 0.0f;
                                boolean isTop10 = false;
                                boolean isShared = false;
                                String customName = null;
                                int daysRemaining = -1;
                                boolean isExpired = false;
                                try {
                                    String progressSearch = "\"progress_percentage\":";
                                    int progressStart = folderPart.indexOf(progressSearch);
                                    if (progressStart > 0) {
                                        progressStart += progressSearch.length();
                                        
                                        int progressEnd = progressStart;
                                        while (progressEnd < folderPart.length()) {
                                            char c = folderPart.charAt(progressEnd);
                                            if (c == ',' || c == '}' || c == ']' || c == ' ') {
                                                break;
                                            }
                                            progressEnd++;
                                        }
                                        
                                        if (progressEnd > progressStart) {
                                            String progressStr = folderPart.substring(progressStart, progressEnd).trim();
                                            progress = Float.parseFloat(progressStr);
                                        }
                                    }
                                    
                                    // Parse is_top10 field - look in the full JSON response
                                    String searchPattern = "\"folder_name\":\"" + folderName + "\"";
                                    int folderStart = jsonResponse.indexOf(searchPattern);
                                    if (folderStart > 0) {
                                        int folderEnd = jsonResponse.indexOf("}", folderStart);
                                        if (folderEnd > folderStart) {
                                            String folderSection = jsonResponse.substring(folderStart, folderEnd);
                                            String top10Search = "\"is_top10\":";
                                            int top10Start = folderSection.indexOf(top10Search);
                                            if (top10Start > 0) {
                                                top10Start += top10Search.length();
                                                int commaPos = folderSection.indexOf(",", top10Start);
                                                int bracePos = folderSection.indexOf("}", top10Start);
                                                int endPos = (commaPos != -1 && bracePos != -1) ? Math.min(commaPos, bracePos) : 
                                                            (commaPos != -1 ? commaPos : bracePos);
                                                if (endPos > top10Start) {
                                                    String top10Str = folderSection.substring(top10Start, endPos).trim();
                                                    isTop10 = "true".equals(top10Str);
                                                    android.util.Log.d("CloudSave", "Parsed is_top10 for " + folderName + ": " + top10Str + " -> " + isTop10);
                                                }
                                            }
                                            
                                            // Parse is_shared field
                                            String sharedSearch = "\"is_shared\":";
                                            int sharedStart = folderSection.indexOf(sharedSearch);
                                            if (sharedStart > 0) {
                                                sharedStart += sharedSearch.length();
                                                int sharedCommaPos = folderSection.indexOf(",", sharedStart);
                                                int sharedBracePos = folderSection.indexOf("}", sharedStart);
                                                int sharedEndPos = (sharedCommaPos != -1 && sharedBracePos != -1) ? Math.min(sharedCommaPos, sharedBracePos) : 
                                                            (sharedCommaPos != -1 ? sharedCommaPos : sharedBracePos);
                                                if (sharedEndPos > sharedStart) {
                                                    String sharedStr = folderSection.substring(sharedStart, sharedEndPos).trim();
                                                    isShared = "true".equals(sharedStr);
                                                    android.util.Log.d("CloudSave", "Parsed is_shared for " + folderName + ": " + sharedStr + " -> " + isShared);
                                                }
                                            }
                                            
                                            // Parse custom_name field
                                            String customSearch = "\"custom_name\":\"";
                                            int customStart = folderSection.indexOf(customSearch);
                                            if (customStart > 0) {
                                                customStart += customSearch.length();
                                                int customEnd = folderSection.indexOf("\"", customStart);
                                                if (customEnd > customStart) {
                                                    customName = folderSection.substring(customStart, customEnd);
                                                    if ("null".equals(customName) || customName.isEmpty()) {
                                                        customName = null;
                                                    }
                                                    android.util.Log.d("CloudSave", "Parsed custom_name for " + folderName + ": " + customName);
                                                }
                                            }
                                            
                                            // Parse days_remaining field
                                            String daysSearch = "\"days_remaining\":";
                                            int daysStart = folderSection.indexOf(daysSearch);
                                            if (daysStart > 0) {
                                                daysStart += daysSearch.length();
                                                int daysEnd = folderSection.indexOf(",", daysStart);
                                                if (daysEnd == -1) daysEnd = folderSection.indexOf("}", daysStart);
                                                if (daysEnd > daysStart) {
                                                    try {
                                                        String daysStr = folderSection.substring(daysStart, daysEnd).trim();
                                                        if (!"null".equals(daysStr)) {
                                                            daysRemaining = Integer.parseInt(daysStr);
                                                        }
                                                    } catch (Exception e) {}
                                                }
                                            }
                                            
                                            // Parse is_expired field
                                            String expiredSearch = "\"is_expired\":";
                                            int expiredStart = folderSection.indexOf(expiredSearch);
                                            if (expiredStart > 0) {
                                                expiredStart += expiredSearch.length();
                                                int expiredEnd = folderSection.indexOf(",", expiredStart);
                                                if (expiredEnd == -1) expiredEnd = folderSection.indexOf("}", expiredStart);
                                                if (expiredEnd > expiredStart) {
                                                    String expiredStr = folderSection.substring(expiredStart, expiredEnd).trim();
                                                    isExpired = "true".equals(expiredStr);
                                                }
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    android.util.Log.e("CloudSave", "Error parsing folder data: " + e.getMessage());
                                }
                                
                                if (!uploadedFolders.contains(folderName)) {
                                    // Create display name with custom name + timestamp
                                    String displayName = folderName;
                                    if (customName != null && !customName.isEmpty()) {
                                        try {
                                            String timestamp = folderName.substring(folderName.lastIndexOf("_") + 1);
                                            long ts = Long.parseLong(timestamp);
                                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy HH:mm");
                                            String date = sdf.format(new java.util.Date(ts * 1000));
                                            displayName = customName + " - " + date;
                                        } catch (Exception e) {
                                            displayName = customName + " - " + folderName;
                                        }
                                    }
                                    uploadedFolders.add(displayName);
                                    folderProgress.add(progress);
                                    folderTop10Status.add(isTop10);
                                    folderSharedStatus.add(isShared);
                                    folderDaysRemaining.add(daysRemaining);
                                    folderExpiredStatus.add(isExpired);
                                    android.util.Log.d("CloudSave", "Added folder: " + displayName + ", progress: " + progress + ", top10: " + isTop10 + ", shared: " + isShared + ", days: " + daysRemaining);
                                }
                            }
                        }
                    } else {
                        android.util.Log.d("CloudSave", "No 'backups' key found in response");
                    }
                }
                
                // Load community saves  
                final String finalJsonResponse = jsonResponse;
                loadCommunitySaves(gameId, packageName, deviceInfo, finalJsonResponse);
                
            } catch (Exception e) {
                android.util.Log.e("CloudSave", "Error in loadUploadedFolders: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    displayFolders(); // Show what we have
                });
            }
        }).start();
    }
    
    private void loadCommunitySaves(String gameId, String packageName, String deviceInfo, final String userBackupsJson) {
        // Check cache first
        long currentTime = System.currentTimeMillis();
        if (gameId.equals(cachedGameId) && 
            currentTime - lastCommunityUpdate < COMMUNITY_CACHE_DURATION &&
            (!communityTopProgress.isEmpty() || !communityTopLiked.isEmpty())) {
            android.util.Log.i("CloudSave", "Using cached community saves for " + gameId);
            runOnUiThread(() -> displayFolders());
            return;
        }
        
        try {
            String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/community-saves?" +
                "game_id=" + URLEncoder.encode(gameId, "UTF-8") +
                "&package_name=" + URLEncoder.encode(packageName, "UTF-8") +
                "&device_info=" + URLEncoder.encode(deviceInfo, "UTF-8") +
                "&user_progress=0&limit=5";
            
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("ngrok-skip-browser-warning", "true");
            
            communityTopProgress.clear();
            communityTopLiked.clear();
            
            int responseCode = conn.getResponseCode();
            
            if (responseCode == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                String jsonResponse = response.toString();
                
                if (!jsonResponse.trim().isEmpty()) {
                    communityTopProgress = parseCommunitySavesFromCategory(jsonResponse, "progress");
                    communityTopLiked = parseCommunitySavesFromCategory(jsonResponse, "liked");
                    
                    // Update cache
                    cachedGameId = gameId;
                    lastCommunityUpdate = currentTime;
                    
                    android.util.Log.i("CloudSave", "Community saves loaded and cached: " + communityTopProgress.size() + " progress, " + communityTopLiked.size() + " liked");
                } else {
                    android.util.Log.w("CloudSave", "Empty community response");
                }
            } else {
                android.util.Log.w("CloudSave", "Community API error: " + responseCode);
            }
        } catch (Exception e) {
            android.util.Log.e("CloudSave", "Community load failed: " + e.getMessage());
        }
        
        runOnUiThread(() -> {
            // Check if ALL backups are in top 10
            boolean allTop10 = false;
            int top10Count = 0;
            try {
                if (userBackupsJson.contains("\"all_top10\":true")) {
                    allTop10 = true;
                }
                String top10Search = "\"top10_count\":";
                int top10Start = userBackupsJson.indexOf(top10Search);
                if (top10Start > 0) {
                    top10Start += top10Search.length();
                    int top10End = userBackupsJson.indexOf(",", top10Start);
                    if (top10End == -1) top10End = userBackupsJson.indexOf("}", top10Start);
                    if (top10End > top10Start) {
                        top10Count = Integer.parseInt(userBackupsJson.substring(top10Start, top10End).trim());
                    }
                }
            } catch (Exception e) {}
            
            // Show congratulations if ALL backups are top 10
            if (allTop10 && uploadedFolders.size() > 1) {
                showAllTop10Celebration(top10Count);
            }
            displayFolders();
        });
    }
    
    private void displayFolders() {
        // Clear existing content
        folderListLayout.removeAllViews();
        
        // Add community saves section
        addCommunitySection();
        
        // Add user backups section
        addUserBackupsSection();
    }
    
    private void addCommunitySection() {
        // Community header
        TextView communityHeader = new TextView(this);
        communityHeader.setText("🌟 COMMUNITY SAVES");
        communityHeader.setTextSize(16);
        communityHeader.setTextColor(0xFF1B365D);
        communityHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        communityHeader.setPadding(0, 0, 0, 12);
        folderListLayout.addView(communityHeader);
        
        if (communityTopProgress.isEmpty() && communityTopLiked.isEmpty()) {
            // No community saves
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable emptyBg = new android.graphics.drawable.GradientDrawable();
            emptyBg.setColor(0xFFF8F9FA);
            emptyBg.setCornerRadius(8);
            emptyBg.setStroke(1, 0xFFE0E0E0);
            emptyCard.setBackground(emptyBg);
            emptyCard.setPadding(16, 16, 16, 16);
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            emptyParams.setMargins(0, 0, 0, 16);
            emptyCard.setLayoutParams(emptyParams);
            
            TextView noComm = new TextView(this);
            noComm.setText("No community saves available yet");
            noComm.setTextColor(0xFF6C757D);
            noComm.setTextSize(14);
            noComm.setGravity(android.view.Gravity.CENTER);
            emptyCard.addView(noComm);
            
            folderListLayout.addView(emptyCard);
        } else {
            // Add top progress saves
            if (!communityTopProgress.isEmpty()) {
                TextView progressLabel = new TextView(this);
                progressLabel.setText("🏆 Top Progress");
                progressLabel.setTextSize(14);
                progressLabel.setTextColor(0xFFFF6B35);
                progressLabel.setTypeface(null, android.graphics.Typeface.BOLD);
                progressLabel.setPadding(8, 0, 0, 8);
                folderListLayout.addView(progressLabel);
                
                for (int i = 0; i < Math.min(5, communityTopProgress.size()); i++) {
                    CommunitySave save = communityTopProgress.get(i);
                    LinearLayout saveCard = createCommunitySaveCard(save, i + 1, "progress");
                    folderListLayout.addView(saveCard);
                }
            }
            
            // Add top liked saves
            if (!communityTopLiked.isEmpty()) {
                TextView likedLabel = new TextView(this);
                likedLabel.setText("❤️ Most Loved");
                likedLabel.setTextSize(14);
                likedLabel.setTextColor(0xFFE91E63);
                likedLabel.setTypeface(null, android.graphics.Typeface.BOLD);
                likedLabel.setPadding(8, 8, 0, 8);
                folderListLayout.addView(likedLabel);
                
                for (int i = 0; i < Math.min(5, communityTopLiked.size()); i++) {
                    CommunitySave save = communityTopLiked.get(i);
                    LinearLayout saveCard = createCommunitySaveCard(save, i + 1, "liked");
                    folderListLayout.addView(saveCard);
                }
            }
        }
        
        // Spacer
        android.view.View spacer = new android.view.View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 24);
        spacer.setLayoutParams(spacerParams);
        folderListLayout.addView(spacer);
    }
    
    private void addUserBackupsSection() {
        // User backups header
        TextView backupsHeader = new TextView(this);
        backupsHeader.setText("📁 YOUR BACKUPS");
        backupsHeader.setTextSize(16);
        backupsHeader.setTextColor(0xFF1B365D);
        backupsHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        backupsHeader.setPadding(0, 0, 0, 12);
        folderListLayout.addView(backupsHeader);
        
        if (uploadedFolders.isEmpty()) {
            LinearLayout emptyCard = new LinearLayout(this);
            emptyCard.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable emptyBg = new android.graphics.drawable.GradientDrawable();
            emptyBg.setColor(0xFFF8F9FA);
            emptyBg.setCornerRadius(8);
            emptyBg.setStroke(1, 0xFFE0E0E0);
            emptyCard.setBackground(emptyBg);
            emptyCard.setPadding(16, 16, 16, 16);
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            emptyCard.setLayoutParams(emptyParams);
            
            TextView noBackups = new TextView(this);
            noBackups.setText("No backups found\n\nUpload some saves first to see them here");
            noBackups.setTextColor(0xFF6C757D);
            noBackups.setTextSize(14);
            noBackups.setGravity(android.view.Gravity.CENTER);
            emptyCard.addView(noBackups);
            
            folderListLayout.addView(emptyCard);
        } else {
            addUserBackupCards();
        }
    }
    
    private void addUserBackupCards() {
        for (int index = 0; index < uploadedFolders.size(); index++) {
            String folderName = uploadedFolders.get(index);
            float progress = index < folderProgress.size() ? folderProgress.get(index) : 0.0f;
            String timestamp = folderName.substring(folderName.lastIndexOf("_") + 1);
            
            // Check if this backup is in top 10 community saves
            boolean isTop10 = index < folderTop10Status.size() ? folderTop10Status.get(index) : false;
            boolean isShared = index < folderSharedStatus.size() ? folderSharedStatus.get(index) : false;
            int daysRemaining = index < folderDaysRemaining.size() ? folderDaysRemaining.get(index) : -1;
            boolean isExpired = index < folderExpiredStatus.size() ? folderExpiredStatus.get(index) : false;
            
            LinearLayout folderCard = new LinearLayout(this);
            folderCard.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable folderBg = new android.graphics.drawable.GradientDrawable();
            if (isTop10) {
                folderBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFFFFD700, 0xFFFFA500});
            } else {
                folderBg.setColor(0xFFFFFFFF);
            }
            folderBg.setCornerRadius(8);
            folderBg.setStroke(isTop10 ? 2 : 1, isTop10 ? 0xFFFFD700 : 0xFF1B365D);
            folderCard.setBackground(folderBg);
            folderCard.setElevation(isTop10 ? 8 : 2);
            folderCard.setPadding(16, 16, 16, 16);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, 8);
            folderCard.setLayoutParams(cardParams);
            
            // Header with icon and date
            LinearLayout headerRow = new LinearLayout(this);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            
            TextView backupIcon = new TextView(this);
            backupIcon.setText(isTop10 ? "🏆" : "📁");
            backupIcon.setTextSize(18);
            backupIcon.setPadding(0, 0, 8, 0);
            headerRow.addView(backupIcon);
            
            TextView dateText = new TextView(this);
            // Display custom name if available, otherwise show folder name
            String displayText = folderName;
            if (folderName.contains(" - ")) {
                // Already formatted with custom name + date
                displayText = folderName;
            } else {
                try {
                    long ts = Long.parseLong(timestamp);
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy HH:mm");
                    String date = sdf.format(new java.util.Date(ts * 1000));
                    displayText = "Backup from " + date;
                } catch (Exception e) {
                    displayText = folderName;
                }
            }
            dateText.setText(displayText);
            
            // Add expiration status
            if (daysRemaining >= 0 && !isTop10) {
                TextView expirationText = new TextView(this);
                if (isExpired) {
                    expirationText.setText("⚠️ EXPIRED - Refresh needed");
                    expirationText.setTextColor(0xFFFF0000);
                } else if (daysRemaining <= 3) {
                    expirationText.setText("⚠️ Expires in " + daysRemaining + " days");
                    expirationText.setTextColor(0xFFFF6600);
                } else {
                    expirationText.setText("📅 Expires in " + daysRemaining + " days");
                    expirationText.setTextColor(0xFF666666);
                }
                expirationText.setTextSize(10);
                expirationText.setPadding(0, 4, 0, 0);
                headerRow.addView(expirationText);
            } else if (isTop10) {
                TextView premiumText = new TextView(this);
                premiumText.setText("♾️ Never expires (Top 10)");
                premiumText.setTextColor(0xFF00AA00);
                premiumText.setTextSize(10);
                premiumText.setPadding(0, 4, 0, 0);
                headerRow.addView(premiumText);
            }
            dateText.setTextColor(isTop10 ? 0xFF8B4513 : 0xFF1B365D);
            dateText.setTextSize(14);
            dateText.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            dateText.setLayoutParams(textParams);
            headerRow.addView(dateText);
            
            folderCard.addView(headerRow);
            
            // Top 10 celebration message with rank
            if (isTop10) {
                TextView celebrationMsg = new TextView(this);
                
                String message = "🎉 🏆 You're in TOP 10! 🌟\n🔒 This save is PROTECTED & FREE! Can't be deleted! 🎆";
                
                celebrationMsg.setText(message);
                celebrationMsg.setTextSize(11);
                celebrationMsg.setTextColor(0xFF8B4513);
                celebrationMsg.setTypeface(null, android.graphics.Typeface.BOLD);
                celebrationMsg.setPadding(8, 8, 8, 8);
                android.graphics.drawable.GradientDrawable msgBg = new android.graphics.drawable.GradientDrawable();
                msgBg.setColor(0x33FFD700);
                msgBg.setCornerRadius(8);
                celebrationMsg.setBackground(msgBg);
                folderCard.addView(celebrationMsg);
            }
            
            // Progress display
            LinearLayout progressRow = new LinearLayout(this);
            progressRow.setOrientation(LinearLayout.HORIZONTAL);
            progressRow.setPadding(0, 8, 0, 0);
            
            TextView progressIcon = new TextView(this);
            progressIcon.setText("📊");
            progressIcon.setTextSize(14);
            progressIcon.setPadding(0, 0, 8, 0);
            progressRow.addView(progressIcon);
            
            TextView progressText = new TextView(this);
            progressText.setText("Game Progress: " + progress + "%");
            progressText.setTextColor(0xFF6C757D);
            progressText.setTextSize(12);
            progressText.setTypeface(null, android.graphics.Typeface.BOLD);
            progressRow.addView(progressText);
            
            android.widget.ProgressBar progressBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setProgress((int) progress);
            progressBar.setMax(100);
            LinearLayout.LayoutParams progressBarParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 20);
            progressBarParams.setMargins(0, 4, 0, 0);
            progressBar.setLayoutParams(progressBarParams);
            
            folderCard.addView(progressRow);
            folderCard.addView(progressBar);
            
            // Action buttons
            LinearLayout buttonRow = new LinearLayout(this);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            buttonRow.setPadding(0, 8, 0, 0);
            
            Button downloadButton = new Button(this);
            downloadButton.setText("📥 Preview Files");
            downloadButton.setTextColor(0xFFFFFFFF);
            android.graphics.drawable.GradientDrawable downloadBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1B365D, 0xFF6C757D});
            downloadBg.setCornerRadius(20);
            downloadButton.setBackground(downloadBg);
            downloadButton.setElevation(4);
            downloadButton.setTextSize(12);
            downloadButton.setOnClickListener(v -> downloadFolder(folderName));
            LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            downloadParams.setMargins(0, 0, 4, 0);
            downloadButton.setLayoutParams(downloadParams);
            
            // Action buttons based on sharing and top10 status
            if (isShared && isTop10) {
                // Shared + Top 10: Only celebrate button
                Button actionButton = new Button(this);
                actionButton.setText("🎉 Celebrate");
                actionButton.setTextColor(0xFFFFFFFF);
                android.graphics.drawable.GradientDrawable happyBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFFFF6B35, 0xFFFF1493});
                happyBg.setCornerRadius(20);
                actionButton.setBackground(happyBg);
                actionButton.setOnClickListener(v -> showMagicalCelebration(timestamp, progress));
                actionButton.setElevation(4);
                actionButton.setTextSize(12);
                LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                actionParams.setMargins(4, 0, 0, 0);
                actionButton.setLayoutParams(actionParams);
                buttonRow.addView(actionButton);
            } else if (isShared) {
                // Shared but not top 10: Delete + Shared badge
                Button deleteButton = new Button(this);
                deleteButton.setText("🗑 Delete");
                deleteButton.setTextColor(0xFFFFFFFF);
                android.graphics.drawable.GradientDrawable deleteBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFFFF4444, 0xFFCC0000});
                deleteBg.setCornerRadius(20);
                deleteButton.setBackground(deleteBg);
                deleteButton.setOnClickListener(v -> confirmDeleteBackup(timestamp));
                deleteButton.setElevation(4);
                deleteButton.setTextSize(12);
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                deleteParams.setMargins(4, 0, 0, 0);
                deleteButton.setLayoutParams(deleteParams);
                buttonRow.addView(deleteButton);
            } else {
                // Private: Delete + Share buttons
                Button deleteButton = new Button(this);
                deleteButton.setText("🗑 Delete");
                deleteButton.setTextColor(0xFFFFFFFF);
                android.graphics.drawable.GradientDrawable deleteBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFFFF4444, 0xFFCC0000});
                deleteBg.setCornerRadius(20);
                deleteButton.setBackground(deleteBg);
                deleteButton.setOnClickListener(v -> confirmDeleteBackup(timestamp));
                deleteButton.setElevation(4);
                deleteButton.setTextSize(10);
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                deleteParams.setMargins(4, 0, 2, 0);
                deleteButton.setLayoutParams(deleteParams);
                
                Button shareButton = new Button(this);
                shareButton.setText("🌍 Share");
                shareButton.setTextColor(0xFFFFFFFF);
                android.graphics.drawable.GradientDrawable shareBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF28A745, 0xFF155724});
                shareBg.setCornerRadius(20);
                shareButton.setBackground(shareBg);
                shareButton.setOnClickListener(v -> shareBackupWithCommunity(timestamp));
                shareButton.setElevation(4);
                shareButton.setTextSize(10);
                LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                shareParams.setMargins(2, 0, 0, 0);
                shareButton.setLayoutParams(shareParams);
                
                Button refreshButton = new Button(this);
                refreshButton.setText("🔄 Refresh");
                refreshButton.setTextColor(0xFFFFFFFF);
                android.graphics.drawable.GradientDrawable refreshBg = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF007BFF, 0xFF0056CC});
                refreshBg.setCornerRadius(20);
                refreshButton.setBackground(refreshBg);
                refreshButton.setOnClickListener(v -> showRefreshAdDialog(timestamp));
                refreshButton.setElevation(4);
                refreshButton.setTextSize(9);
                LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                refreshParams.setMargins(2, 0, 2, 0);
                refreshButton.setLayoutParams(refreshParams);
                
                buttonRow.addView(deleteButton);
                buttonRow.addView(refreshButton);
                buttonRow.addView(shareButton);
            }
            // Download button is always first
            buttonRow.addView(downloadButton);
            folderCard.addView(buttonRow);
            
            folderListLayout.addView(folderCard);
        }
    }
    
    private LinearLayout createCommunitySaveCard(CommunitySave save, int rank, String category) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag("collapsed"); // Track expansion state
        
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            "progress".equals(category) ? 
                new int[]{0xFFFF6B35, 0xFFF7931E} : 
                new int[]{0xFFE91E63, 0xFFAD1457});
        cardBg.setCornerRadius(12);
        card.setBackground(cardBg);
        card.setElevation(4);
        card.setPadding(16, 12, 16, 12);
        
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 8);
        card.setLayoutParams(cardParams);
        
        // Header row with profile image
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        TextView rankText = new TextView(this);
        String rankEmoji = "progress".equals(category) ? getRankEmoji(rank) : "❤️";
        rankText.setText(rankEmoji + " #" + rank);
        rankText.setTextSize(14);
        rankText.setTextColor(0xFFFFFFFF);
        rankText.setTypeface(null, android.graphics.Typeface.BOLD);
        headerRow.addView(rankText);
        
        android.view.View spacer = new android.view.View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 0);
        spacerParams.weight = 1;
        spacer.setLayoutParams(spacerParams);
        headerRow.addView(spacer);
        
        // Profile image
        ImageView profileImage = new ImageView(this);
        LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(40, 40);
        imgParams.setMargins(0, 0, 8, 0);
        profileImage.setLayoutParams(imgParams);
        loadProfileImage(profileImage, save.profileImage);
        headerRow.addView(profileImage);
        
        TextView playerText = new TextView(this);
        // Check if this is user's own save
        boolean isOwnSave = false;
        try {
            // Compare user IDs - if save.userId matches current user's ID, it's their own save
            if (currentUserId != null && save.userId != null && save.userId.equals(currentUserId)) {
                isOwnSave = true;
            }
        } catch (Exception e) {}
        
        if (isOwnSave) {
            playerText.setText("⭐ YOUR SAVE");
            playerText.setTextSize(12);
            playerText.setTextColor(0xFFFFD700);
            playerText.setTypeface(null, android.graphics.Typeface.BOLD);
            // Add golden background
            android.graphics.drawable.GradientDrawable ownSaveBg = new android.graphics.drawable.GradientDrawable();
            ownSaveBg.setColor(0x33FFD700);
            ownSaveBg.setCornerRadius(8);
            playerText.setBackground(ownSaveBg);
            playerText.setPadding(8, 4, 8, 4);
        } else {
            // Format name with premium status
            String displayName = save.playerName;
            if (save.isPremium) {
                displayName = "✓ " + save.playerName + " Premium";
            }
            playerText.setText(displayName);
            playerText.setTextSize(12);
            playerText.setTextColor(save.isPremium ? 0xFFFFD700 : 0xFFFFFFFF);
            playerText.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        headerRow.addView(playerText);
        
        card.addView(headerRow);
        
        // Stats row
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, 4, 0, 0);
        
        TextView progressText = new TextView(this);
        progressText.setText("📊 " + save.progress + "%");
        progressText.setTextSize(11);
        progressText.setTextColor(0xFFE8F4FD);
        statsRow.addView(progressText);
        
        android.view.View statsSpacer = new android.view.View(this);
        LinearLayout.LayoutParams statsSpacerParams = new LinearLayout.LayoutParams(0, 0);
        statsSpacerParams.weight = 1;
        statsSpacer.setLayoutParams(statsSpacerParams);
        statsRow.addView(statsSpacer);
        
        TextView likesText = new TextView(this);
        likesText.setText("👍 " + save.likes + " 👎 " + save.dislikes + " 📥 " + save.downloads);
        likesText.setTextSize(11);
        likesText.setTextColor(0xFFE8F4FD);
        statsRow.addView(likesText);
        
        card.addView(statsRow);
        
        // Recent comment preview
        if (!save.recentComments.isEmpty()) {
            CommunitySave.Comment recentComment = save.recentComments.get(0);
            TextView commentPreview = new TextView(this);
            String commentText = recentComment.comment.length() > 30 ? 
                recentComment.comment.substring(0, 30) + "..." : recentComment.comment;
            commentPreview.setText("💬 " + recentComment.playerName + ": \"" + commentText + "\"");
            commentPreview.setTextSize(10);
            commentPreview.setTextColor(0xFFE8F4FD);
            commentPreview.setPadding(0, 4, 0, 0);
            card.addView(commentPreview);
        }
        
        // Expand hint
        TextView expandHint = new TextView(this);
        expandHint.setText("👆 Tap to expand");
        expandHint.setTextSize(9);
        expandHint.setTextColor(0xFFD1C4E9);
        expandHint.setTypeface(null, android.graphics.Typeface.ITALIC);
        expandHint.setPadding(0, 4, 0, 0);
        expandHint.setGravity(android.view.Gravity.CENTER);
        card.addView(expandHint);
        
        // Expanded content (initially hidden)
        LinearLayout expandedContent = createExpandedContent(save);
        expandedContent.setVisibility(LinearLayout.GONE);
        card.addView(expandedContent);
        
        // Click to expand/collapse
        card.setOnClickListener(v -> toggleCardExpansion(card, expandHint, expandedContent));
        
        return card;
    }
    
    private LinearLayout createExpandedContent(CommunitySave save) {
        LinearLayout expandedLayout = new LinearLayout(this);
        expandedLayout.setOrientation(LinearLayout.VERTICAL);
        expandedLayout.setPadding(0, 8, 0, 0);
        
        // All comments section
        TextView commentsHeader = new TextView(this);
        commentsHeader.setText("💬 All Comments:");
        commentsHeader.setTextSize(12);
        commentsHeader.setTextColor(0xFFFFFFFF);
        commentsHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        expandedLayout.addView(commentsHeader);
        
        // Comments list
        LinearLayout commentsList = new LinearLayout(this);
        commentsList.setOrientation(LinearLayout.VERTICAL);
        commentsList.setPadding(8, 4, 0, 8);
        
        if (save.recentComments.isEmpty()) {
            TextView noComments = new TextView(this);
            noComments.setText("No comments yet");
            noComments.setTextSize(10);
            noComments.setTextColor(0xFFD1C4E9);
            noComments.setTypeface(null, android.graphics.Typeface.ITALIC);
            commentsList.addView(noComments);
        } else {
            for (CommunitySave.Comment comment : save.recentComments) {
                LinearLayout commentRow = createCommentRow(comment, save.saveId);
                commentsList.addView(commentRow);
            }
        }
        
        expandedLayout.addView(commentsList);
        
        // Add comment section
        LinearLayout addCommentSection = createAddCommentSection(save.saveId);
        expandedLayout.addView(addCommentSection);
        
        // Support buttons if donation links exist
        if ((save.patreonLink != null && !save.patreonLink.isEmpty()) || 
            (save.buymeacoffeeLink != null && !save.buymeacoffeeLink.isEmpty())) {
            
            TextView supportLabel = new TextView(this);
            supportLabel.setText("💝 Do you like this save? Please support " + save.playerName + ":");
            supportLabel.setTextSize(12);
            supportLabel.setTextColor(0xFFFFFFFF);
            supportLabel.setTypeface(null, android.graphics.Typeface.BOLD);
            supportLabel.setPadding(0, 8, 0, 5);
            expandedLayout.addView(supportLabel);
            
            LinearLayout supportButtons = new LinearLayout(this);
            supportButtons.setOrientation(LinearLayout.HORIZONTAL);
            supportButtons.setPadding(0, 0, 0, 8);
            
            if (save.patreonLink != null && !save.patreonLink.isEmpty()) {
                Button patreonBtn = new Button(this);
                patreonBtn.setText("🎯 Patreon");
                patreonBtn.setTextSize(11);
                patreonBtn.setBackgroundColor(0xFFf96854);
                patreonBtn.setTextColor(0xFFFFFFFF);
                patreonBtn.setPadding(15, 8, 15, 8);
                patreonBtn.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(save.patreonLink));
                    startActivity(intent);
                });
                
                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnParams.setMargins(0, 0, 10, 0);
                patreonBtn.setLayoutParams(btnParams);
                supportButtons.addView(patreonBtn);
            }
            
            if (save.buymeacoffeeLink != null && !save.buymeacoffeeLink.isEmpty()) {
                Button coffeeBtn = new Button(this);
                coffeeBtn.setText("☕ Buy Me Coffee");
                coffeeBtn.setTextSize(11);
                coffeeBtn.setBackgroundColor(0xFFffdd00);
                coffeeBtn.setTextColor(0xFF000000);
                coffeeBtn.setPadding(15, 8, 15, 8);
                coffeeBtn.setOnClickListener(v -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(save.buymeacoffeeLink));
                    startActivity(intent);
                });
                supportButtons.addView(coffeeBtn);
            }
            
            expandedLayout.addView(supportButtons);
        }
        
        // Download button
        Button downloadBtn = new Button(this);
        downloadBtn.setText("📥 Preview Files");
        downloadBtn.setTextColor(0xFF1B365D);
        downloadBtn.setBackgroundColor(0xFFFFFFFF);
        downloadBtn.setTextSize(12);
        downloadBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(0xFFFFFFFF);
        btnBg.setCornerRadius(20);
        downloadBtn.setBackground(btnBg);
        downloadBtn.setOnClickListener(v -> previewCommunityFiles(save));
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, 8, 0, 0);
        downloadBtn.setLayoutParams(btnParams);
        expandedLayout.addView(downloadBtn);
        
        return expandedLayout;
    }
    
    private LinearLayout createCommentRow(CommunitySave.Comment comment, String saveId) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 2, 0, 2);
        
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.TOP);
        
        // Comment profile image
        ImageView commentProfileImage = new ImageView(this);
        LinearLayout.LayoutParams commentImgParams = new LinearLayout.LayoutParams(30, 30);
        commentImgParams.setMargins(0, 0, 8, 0);
        commentProfileImage.setLayoutParams(commentImgParams);
        loadProfileImage(commentProfileImage, comment.profileImage);
        headerRow.addView(commentProfileImage);
        
        LinearLayout commentTextLayout = new LinearLayout(this);
        commentTextLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLayoutParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
        textLayoutParams.weight = 1;
        commentTextLayout.setLayoutParams(textLayoutParams);
        
        TextView commentText = new TextView(this);
        String likeIcon = comment.isLike ? "👍" : "👎";
        String displayName = comment.playerName;
        if (comment.isPremium) {
            displayName = "✓ " + comment.playerName + " Premium";
        }
        commentText.setText(likeIcon + " " + displayName + ": \"" + comment.comment + "\"");
        commentText.setTextSize(10);
        commentText.setTextColor(comment.isPremium ? 0xFFFFD700 : 0xFFFFFFFF);
        commentTextLayout.addView(commentText);
        
        headerRow.addView(commentTextLayout);
        
        // Check if this is user's own comment
        if (currentUserId != null && comment.playerName.equals(getCurrentUserName())) {
            Button editBtn = new Button(this);
            editBtn.setText("✏️");
            editBtn.setTextSize(10);
            editBtn.setBackgroundColor(0x00000000);
            editBtn.setTextColor(0xFFFFFFFF);
            editBtn.setPadding(8, 0, 8, 0);
            editBtn.setOnClickListener(v -> editComment(saveId, comment));
            headerRow.addView(editBtn);
            
            Button deleteBtn = new Button(this);
            deleteBtn.setText("🗑️");
            deleteBtn.setTextSize(10);
            deleteBtn.setBackgroundColor(0x00000000);
            deleteBtn.setTextColor(0xFFFFFFFF);
            deleteBtn.setPadding(8, 0, 8, 0);
            deleteBtn.setOnClickListener(v -> deleteComment(saveId));
            headerRow.addView(deleteBtn);
        }
        
        row.addView(headerRow);
        
        TextView dateText = new TextView(this);
        dateText.setText(comment.date);
        dateText.setTextSize(8);
        dateText.setTextColor(0xFFD1C4E9);
        dateText.setPadding(16, 0, 0, 4);
        row.addView(dateText);
        
        return row;
    }
    
    private void loadProfileImage(ImageView imageView, String profileImageBase64) {
        if (profileImageBase64 != null && !profileImageBase64.isEmpty()) {
            try {
                byte[] decodedBytes = android.util.Base64.decode(profileImageBase64, android.util.Base64.DEFAULT);
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
                
                if (bitmap != null) {
                    // Create circular bitmap
                    android.graphics.Bitmap circularBitmap = createCircularBitmap(bitmap);
                    imageView.setImageBitmap(circularBitmap);
                    return;
                }
            } catch (Exception e) {
                // Fall through to default
            }
        }
        
        // Default avatar
        createDefaultAvatar(imageView);
    }
    
    private android.graphics.Bitmap createCircularBitmap(android.graphics.Bitmap bitmap) {
        int size = Math.min(bitmap.getWidth(), bitmap.getHeight());
        android.graphics.Bitmap output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        
        android.graphics.Canvas canvas = new android.graphics.Canvas(output);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setAntiAlias(true);
        
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, 0, 0, paint);
        
        return output;
    }
    
    private void createDefaultAvatar(ImageView imageView) {
        int size = 100;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        
        // Background circle
        android.graphics.Paint bgPaint = new android.graphics.Paint();
        bgPaint.setColor(0xFF58a6ff);
        bgPaint.setAntiAlias(true);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, bgPaint);
        
        // User icon
        android.graphics.Paint iconPaint = new android.graphics.Paint();
        iconPaint.setColor(0xFFFFFFFF);
        iconPaint.setAntiAlias(true);
        iconPaint.setTextSize(size * 0.6f);
        iconPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        
        android.graphics.Rect bounds = new android.graphics.Rect();
        iconPaint.getTextBounds("👤", 0, 1, bounds);
        float y = size / 2f + bounds.height() / 2f;
        canvas.drawText("👤", size / 2f, y, iconPaint);
        
        imageView.setImageBitmap(bitmap);
    }
    
    private LinearLayout createAddCommentSection(String saveId) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, 8, 0, 0);
        
        TextView addHeader = new TextView(this);
        addHeader.setText("💬 Add Your Comment:");
        addHeader.setTextSize(11);
        addHeader.setTextColor(0xFFFFFFFF);
        addHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        section.addView(addHeader);
        
        android.widget.EditText commentInput = new android.widget.EditText(this);
        commentInput.setHint("Share your thoughts (max 50 words)...");
        commentInput.setTextSize(10);
        commentInput.setTextColor(0xFF1B365D);
        commentInput.setBackgroundColor(0xFFFFFFFF);
        commentInput.setPadding(8, 8, 8, 8);
        commentInput.setMaxLines(3);
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(0xFFFFFFFF);
        inputBg.setCornerRadius(8);
        commentInput.setBackground(inputBg);
        section.addView(commentInput);
        
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, 4, 0, 0);
        
        Button likeBtn = new Button(this);
        likeBtn.setText("👍 Like & Comment");
        likeBtn.setTextSize(10);
        likeBtn.setBackgroundColor(0xFF28A745);
        likeBtn.setTextColor(0xFFFFFFFF);
        likeBtn.setOnClickListener(v -> submitComment(saveId, commentInput.getText().toString(), true));
        LinearLayout.LayoutParams likeParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
        likeParams.weight = 1;
        likeParams.setMargins(0, 0, 4, 0);
        likeBtn.setLayoutParams(likeParams);
        buttonRow.addView(likeBtn);
        
        Button dislikeBtn = new Button(this);
        dislikeBtn.setText("👎 Dislike & Comment");
        dislikeBtn.setTextSize(10);
        dislikeBtn.setBackgroundColor(0xFFDC3545);
        dislikeBtn.setTextColor(0xFFFFFFFF);
        dislikeBtn.setOnClickListener(v -> submitComment(saveId, commentInput.getText().toString(), false));
        LinearLayout.LayoutParams dislikeParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
        dislikeParams.weight = 1;
        dislikeParams.setMargins(4, 0, 0, 0);
        dislikeBtn.setLayoutParams(dislikeParams);
        buttonRow.addView(dislikeBtn);
        
        section.addView(buttonRow);
        
        return section;
    }
    
    private void toggleCardExpansion(LinearLayout card, TextView hint, LinearLayout expandedContent) {
        String state = (String) card.getTag();
        if ("collapsed".equals(state)) {
            // Expand
            expandedContent.setVisibility(LinearLayout.VISIBLE);
            hint.setText("👆 Tap to collapse");
            card.setTag("expanded");
        } else {
            // Collapse
            expandedContent.setVisibility(LinearLayout.GONE);
            hint.setText("👆 Tap to expand");
            card.setTag("collapsed");
        }
    }
    
    private void submitComment(String saveId, String comment, boolean isLike) {
        if (comment.trim().isEmpty()) {
            Toast.makeText(this, "Please enter a comment", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (comment.split("\\s+").length > 50) {
            Toast.makeText(this, "Comment must be 50 words or less", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Check if user already commented
        checkExistingComment(saveId, comment, isLike);
    }
    
    private void checkExistingComment(String saveId, String newComment, boolean newIsLike) {
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/comments?" +
                    "save_id=" + java.net.URLEncoder.encode(saveId, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8");
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    org.json.JSONObject jsonObj = new org.json.JSONObject(jsonResponse);
                    org.json.JSONArray comments = jsonObj.optJSONArray("comments");
                    
                    String existingComment = null;
                    boolean existingIsLike = false;
                    
                    if (comments != null) {
                        for (int i = 0; i < comments.length(); i++) {
                            org.json.JSONObject commentObj = comments.getJSONObject(i);
                            if (commentObj.optBoolean("is_own_comment", false)) {
                                existingComment = commentObj.optString("comment", "");
                                existingIsLike = commentObj.optBoolean("is_like", false);
                                break;
                            }
                        }
                    }
                    
                    final String finalExistingComment = existingComment;
                    final boolean finalExistingIsLike = existingIsLike;
                    
                    runOnUiThread(() -> {
                        if (finalExistingComment != null) {
                            showUpdateCommentDialog(saveId, finalExistingComment, finalExistingIsLike, newComment, newIsLike);
                        } else {
                            proceedWithComment(saveId, newComment, newIsLike);
                        }
                    });
                } else {
                    runOnUiThread(() -> proceedWithComment(saveId, newComment, newIsLike));
                }
            } catch (Exception e) {
                runOnUiThread(() -> proceedWithComment(saveId, newComment, newIsLike));
            }
        }).start();
    }
    
    private void showUpdateCommentDialog(String saveId, String existingComment, boolean existingIsLike, String newComment, boolean newIsLike) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Update Your Comment");
        builder.setMessage("You already commented on this save. You can only have one comment per save.");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        
        TextView currentLabel = new TextView(this);
        currentLabel.setText("Your current comment:");
        currentLabel.setTextSize(14);
        currentLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(currentLabel);
        
        TextView currentComment = new TextView(this);
        currentComment.setText((existingIsLike ? "👍" : "👎") + " \"" + existingComment + "\"");
        currentComment.setTextSize(12);
        currentComment.setPadding(0, 8, 0, 16);
        layout.addView(currentComment);
        
        TextView newLabel = new TextView(this);
        newLabel.setText("Edit your comment:");
        newLabel.setTextSize(14);
        newLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(newLabel);
        
        android.widget.EditText editText = new android.widget.EditText(this);
        editText.setText(existingComment);
        editText.setMaxLines(3);
        layout.addView(editText);
        
        builder.setView(layout);
        
        builder.setPositiveButton("👍 Update Like", (dialog, which) -> {
            String updatedComment = editText.getText().toString().trim();
            if (!updatedComment.isEmpty() && updatedComment.split("\\s+").length <= 50) {
                proceedWithComment(saveId, updatedComment, true);
            } else {
                Toast.makeText(this, "Invalid comment (max 50 words)", Toast.LENGTH_SHORT).show();
            }
        });
        
        builder.setNeutralButton("👎 Update Dislike", (dialog, which) -> {
            String updatedComment = editText.getText().toString().trim();
            if (!updatedComment.isEmpty() && updatedComment.split("\\s+").length <= 50) {
                proceedWithComment(saveId, updatedComment, false);
            } else {
                Toast.makeText(this, "Invalid comment (max 50 words)", Toast.LENGTH_SHORT).show();
            }
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void proceedWithComment(String saveId, String comment, boolean isLike) {
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                
                String postData = "{\"save_id\":\"" + saveId + "\"," +
                    "\"is_like\":" + isLike + "," +
                    "\"comment\":\"" + comment.replace("\"", "\\\"") + "\"," +
                    "\"package_name\":\"" + packageName + "\"," +
                    "\"device_info\":\"" + deviceInfo + "\"}";
                
                java.net.URL url = new java.net.URL(CloudSaveConfig.getApiUrl() + "/api/saves/rate");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                java.io.OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes());
                os.close();
                
                int responseCode = conn.getResponseCode();
                
                runOnUiThread(() -> {
                    if (responseCode == 200) {
                        Toast.makeText(this, "✅ Comment submitted!", Toast.LENGTH_SHORT).show();
                        // Store in rating history for My Activity
                        storeRatingInActivity(saveId, isLike, comment);
                        // Refresh community saves
                        loadUploadedFolders();
                    } else {
                        Toast.makeText(this, "Failed to submit comment", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void editComment(String saveId, CommunitySave.Comment comment) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("✏️ Edit Comment");
        
        android.widget.EditText editText = new android.widget.EditText(this);
        editText.setText(comment.comment);
        editText.setMaxLines(3);
        builder.setView(editText);
        
        builder.setPositiveButton("Update", (dialog, which) -> {
            String newComment = editText.getText().toString().trim();
            if (!newComment.isEmpty() && newComment.split("\\s+").length <= 50) {
                updateComment(saveId, newComment, comment.isLike);
            } else {
                Toast.makeText(this, "Invalid comment (max 50 words)", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void updateComment(String saveId, String comment, boolean isLike) {
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                
                String postData = "{\"save_id\":\"" + saveId + "\"," +
                    "\"is_like\":" + isLike + "," +
                    "\"comment\":\"" + comment.replace("\"", "\\\"") + "\"," +
                    "\"package_name\":\"" + packageName + "\"," +
                    "\"device_info\":\"" + deviceInfo + "\"}";
                
                java.net.URL url = new java.net.URL(CloudSaveConfig.getApiUrl() + "/api/saves/rate");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                java.io.OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes());
                os.close();
                
                int responseCode = conn.getResponseCode();
                
                runOnUiThread(() -> {
                    if (responseCode == 200) {
                        Toast.makeText(this, "✅ Comment updated!", Toast.LENGTH_SHORT).show();
                        loadUploadedFolders();
                    } else {
                        Toast.makeText(this, "Failed to update comment", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
    
    private void deleteComment(String saveId) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("🗑️ Delete Comment");
        builder.setMessage("Are you sure you want to delete your comment?");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            new Thread(() -> {
                try {
                    String packageName = getPackageName();
                    String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                    
                    String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/rate?" +
                        "save_id=" + java.net.URLEncoder.encode(saveId, "UTF-8") +
                        "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                        "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8");
                    
                    java.net.URL url = new java.net.URL(urlString);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("DELETE");
                    
                    int responseCode = conn.getResponseCode();
                    
                    runOnUiThread(() -> {
                        if (responseCode == 200) {
                            Toast.makeText(this, "✅ Comment deleted!", Toast.LENGTH_SHORT).show();
                            loadUploadedFolders();
                        } else {
                            Toast.makeText(this, "Failed to delete comment", Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private String getCurrentUserName() {
        try {
            String packageName = getPackageName();
            String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            android.content.SharedPreferences prefs = getSharedPreferences("cloudsave", MODE_PRIVATE);
            return prefs.getString("user_name", "Unknown");
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    private void downloadFolder(String displayName) {
        // Extract actual timestamp from display name if needed
        String actualTimestamp = displayName;
        if (displayName.contains(" - ")) {
            // This is a custom name format, need to extract original timestamp
            // Find the original folder name by looking through the parsed data
            for (int i = 0; i < uploadedFolders.size(); i++) {
                if (uploadedFolders.get(i).equals(displayName)) {
                    // Extract timestamp from the original folder structure
                    // Since we stored display names, we need to reverse-engineer the timestamp
                    try {
                        String dateStr = displayName.substring(displayName.lastIndexOf(" - ") + 3);
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MMM dd, yyyy HH:mm");
                        java.util.Date date = sdf.parse(dateStr);
                        actualTimestamp = String.valueOf(date.getTime() / 1000);
                    } catch (Exception e) {
                        // Fallback: use display name as is
                        actualTimestamp = displayName;
                    }
                    break;
                }
            }
        }
        loadCloudFilesPreview(actualTimestamp);
    }
    
    private void loadCloudFilesPreview(String folderTimestamp) {
        Toast.makeText(this, "Loading files from backup...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gamePackage = extractPackageName(selectedGame);
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/backup-files?" +
                    "game_id=" + java.net.URLEncoder.encode(gamePackage, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                    "&folder_timestamp=" + folderTimestamp;
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    java.util.List<String> allFiles = new java.util.ArrayList<>();
                    
                    // Parse root and sync files
                    String[] rootParts = jsonResponse.split("\"root_files\":");
                    if (rootParts.length > 1) {
                        String rootSection = rootParts[1];
                        int endRoot = rootSection.indexOf("],");
                        if (endRoot > 0) {
                            rootSection = rootSection.substring(0, endRoot);
                        }
                        String[] fileParts = rootSection.split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    String[] syncParts = jsonResponse.split("\"sync_files\":");
                    if (syncParts.length > 1) {
                        String syncSection = syncParts[1];
                        int endSync = syncSection.indexOf("],");
                        if (endSync > 0) {
                            syncSection = syncSection.substring(0, endSync);
                        }
                        String[] fileParts = syncSection.split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    runOnUiThread(() -> {
                        if (allFiles.isEmpty()) {
                            Toast.makeText(this, "No files in backup", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        
                        showCloudFilesPreviewDialog(allFiles, folderTimestamp);
                    });
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Failed to load files", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private void showCloudFilesPreviewDialog(java.util.List<String> filenames, String folderTimestamp) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Files in Backup (" + filenames.size() + " files)");
        
        StringBuilder fileList = new StringBuilder();
        for (String filename : filenames) {
            fileList.append("• ").append(filename).append("\n");
        }
        
        TextView textView = new TextView(this);
        textView.setText(fileList.toString());
        textView.setPadding(20, 20, 20, 20);
        textView.setTextSize(12);
        
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(textView);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 600);
        scrollView.setLayoutParams(params);
        
        builder.setView(scrollView);
        builder.setPositiveButton("📥 Download All Files", (dialog, which) -> showDownloadWarning(folderTimestamp));
        builder.setNegativeButton("Cancel", null);
        
        builder.show();
    }
    
    private void confirmDeleteBackup(String timestamp) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Delete Backup");
        builder.setMessage("Are you sure you want to delete this backup? This cannot be undone.");
        builder.setPositiveButton("Delete", (dialog, which) -> deleteBackup(timestamp));
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void deleteBackup(String timestamp) {
        Toast.makeText(this, "Deleting backup...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gameId = extractPackageName(selectedGame);
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/delete-backup?" +
                    "game_id=" + java.net.URLEncoder.encode(gameId, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                    "&folder_timestamp=" + timestamp;
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                
                int responseCode = conn.getResponseCode();
                
                runOnUiThread(() -> {
                    if (responseCode == 200) {
                        Toast.makeText(this, "Backup deleted successfully", Toast.LENGTH_SHORT).show();
                        folderListLayout.removeAllViews();
                        loadUploadedFolders();
                    } else if (responseCode == 403) {
                        try {
                            java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(conn.getErrorStream()));
                            StringBuilder errorResponse = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                errorResponse.append(line);
                            }
                            reader.close();
                            
                            String jsonError = errorResponse.toString();
                            String message = "Cannot delete community save";
                            
                            if (jsonError.contains("\"message\":")) {
                                int start = jsonError.indexOf("\"message\":") + 10;
                                int end = jsonError.indexOf("\"", start + 1);
                                if (end > start) {
                                    message = jsonError.substring(start + 1, end);
                                }
                            }
                            
                            android.app.AlertDialog.Builder protectionBuilder = new android.app.AlertDialog.Builder(this);
                            protectionBuilder.setTitle("🛡️ Community Protected Save");
                            protectionBuilder.setMessage(message);
                            protectionBuilder.setPositiveButton("OK", null);
                            protectionBuilder.show();
                            
                        } catch (Exception e) {
                            Toast.makeText(this, "🛡️ Cannot delete: This save is protected as a top community save!", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(this, "Failed to delete backup", Toast.LENGTH_SHORT).show();
                    }
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private String extractPackageName(String gameDisplayName) {
        int startIndex = gameDisplayName.lastIndexOf("(");
        int endIndex = gameDisplayName.lastIndexOf(")");
        
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            return gameDisplayName.substring(startIndex + 1, endIndex);
        }
        
        return gameDisplayName;
    }
    
    private void downloadCloudBackup(String folderTimestamp) {
        // Show progress overlay
        showProgressOverlay();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gamePackage = extractPackageName(selectedGame);
                
                // Get app name for save directory
                String appName = "Unknown";
                try {
                    android.content.pm.ApplicationInfo appInfo = getPackageManager().getApplicationInfo(gamePackage, 0);
                    appName = (String) getPackageManager().getApplicationLabel(appInfo);
                } catch (Exception e) {
                    appName = gamePackage.substring(gamePackage.lastIndexOf('.') + 1);
                }
                
                // Create save directory
                String gameFolder = android.os.Environment.getExternalStorageDirectory() + "/Documents/AdultModGames/" + appName;
                java.io.File gameFolderDir = new java.io.File(gameFolder);
                
                if (gameFolderDir.exists()) {
                    deleteDirectory(gameFolderDir);
                }
                
                String savePath = gameFolder + "/saves";
                java.io.File saveDir = new java.io.File(savePath);
                saveDir.mkdirs();
                
                // Get files from backup
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/backup-files?" +
                    "game_id=" + java.net.URLEncoder.encode(gamePackage, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                    "&folder_timestamp=" + folderTimestamp;
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    java.util.List<String> allFiles = new java.util.ArrayList<>();
                    
                    // Parse files
                    String[] rootParts = jsonResponse.split("\"root_files\":");
                    if (rootParts.length > 1) {
                        String rootSection = rootParts[1];
                        int endRoot = rootSection.indexOf("],");
                        if (endRoot > 0) {
                            rootSection = rootSection.substring(0, endRoot);
                        }
                        String[] fileParts = rootSection.split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    String[] syncParts = jsonResponse.split("\"sync_files\":");
                    if (syncParts.length > 1) {
                        String syncSection = syncParts[1];
                        int endSync = syncSection.indexOf("],");
                        if (endSync > 0) {
                            syncSection = syncSection.substring(0, endSync);
                        }
                        String[] fileParts = syncSection.split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    final int totalFiles = allFiles.size();
                    
                    // Download all files with progress
                    int downloadedCount = 0;
                    for (int i = 0; i < allFiles.size(); i++) {
                        String filename = allFiles.get(i);
                        final int currentFile = i + 1;
                        final int percentage = (currentFile * 100) / totalFiles;
                        
                        runOnUiThread(() -> {
                            updateProgress(percentage, "Downloading " + filename, currentFile, totalFiles);
                        });
                        
                        boolean success = downloadFileToFolder(packageName, deviceInfo, filename, saveDir);
                        if (success) downloadedCount++;
                    }
                    
                    final int finalCount = downloadedCount;
                    runOnUiThread(() -> {
                        hideProgressOverlay();
                        Toast.makeText(this, "✅ Downloaded " + finalCount + " files to: " + savePath, Toast.LENGTH_LONG).show();
                        // Show ad after successful download
                        android.util.Log.d("ADS_DEBUG", "DownloadSaveActivity: Calling showRewardedAd for Download");
                        UnityAdsHelper.showRewardedAd(this, "Download");
                    });
                } else {
                    runOnUiThread(() -> {
                        hideProgressOverlay();
                        Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideProgressOverlay();
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private boolean downloadFileToFolder(String packageName, String deviceInfo, String filename, java.io.File saveDir) {
        try {
            String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/download?" +
                "game_id=" + java.net.URLEncoder.encode(extractPackageName(selectedGame), "UTF-8") +
                "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                "&file_name=" + java.net.URLEncoder.encode(filename, "UTF-8");
            
            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            
            if (conn.getResponseCode() == 200) {
                java.io.File outputFile = new java.io.File(saveDir, filename);
                
                // Create parent directories if they don't exist
                outputFile.getParentFile().mkdirs();
                
                java.io.InputStream inputStream = conn.getInputStream();
                java.io.FileOutputStream outputStream = new java.io.FileOutputStream(outputFile);
                
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                
                inputStream.close();
                outputStream.close();
                return true;
            }
        } catch (Exception e) {
            android.util.Log.e("CloudSave", "Error downloading file: " + e.getMessage());
        }
        return false;
    }
    
    private void deleteDirectory(java.io.File dir) {
        if (dir.isDirectory()) {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }
    
    private java.util.List<CommunitySave> parseCommunitySavesFromCategory(String jsonResponse, String category) {
        java.util.List<CommunitySave> saves = new java.util.ArrayList<>();
        
        try {
            org.json.JSONObject jsonObj = new org.json.JSONObject(jsonResponse);
            String arrayKey = category.equals("progress") ? "top_progress" : "top_liked";
            org.json.JSONArray categoryArray = jsonObj.optJSONArray(arrayKey);
            
            if (categoryArray != null) {
                for (int i = 0; i < categoryArray.length(); i++) {
                    try {
                        org.json.JSONObject saveObj = categoryArray.getJSONObject(i);
                        CommunitySave save = new CommunitySave();
                        
                        save.saveId = saveObj.optString("save_id", "");
                        save.userId = saveObj.optString("user_id", "");
                        save.folderTimestamp = saveObj.optString("folder_timestamp", "");
                        save.progress = (float) saveObj.optDouble("progress", 0.0);
                        save.playerName = saveObj.optString("player_name", "Anonymous");
                        save.profileImage = saveObj.optString("profile_image", "");
                        save.likes = saveObj.optInt("likes", 0);
                        save.dislikes = saveObj.optInt("dislikes", 0);
                        save.downloads = saveObj.optInt("downloads", 0);
                        save.patreonLink = saveObj.optString("patreon_link", "");
                        save.buymeacoffeeLink = saveObj.optString("buymeacoffee_link", "");
                        save.isPremium = saveObj.optBoolean("is_premium", false);
                        
                        // Parse comments
                        save.recentComments = new java.util.ArrayList<>();
                        org.json.JSONArray commentsArray = saveObj.optJSONArray("recent_comments");
                        if (commentsArray != null) {
                            for (int j = 0; j < commentsArray.length(); j++) {
                                try {
                                    org.json.JSONObject commentObj = commentsArray.getJSONObject(j);
                                    CommunitySave.Comment comment = new CommunitySave.Comment();
                                    comment.playerName = commentObj.optString("player_name", "Anonymous");
                                    comment.comment = commentObj.optString("comment", "");
                                    comment.isLike = commentObj.optBoolean("is_like", false);
                                    comment.date = commentObj.optString("date", "");
                                    comment.profileImage = commentObj.optString("profile_image", "");
                                    comment.isPremium = commentObj.optBoolean("is_premium", false);
                                    save.recentComments.add(comment);
                                } catch (Exception ce) {
                                    // Skip invalid comment
                                }
                            }
                        }
                        
                        saves.add(save);
                        
                    } catch (Exception e) {
                        android.util.Log.w("CloudSave", "Parse error save " + i + ": " + e.getMessage());
                    }
                }
            } else {
                android.util.Log.w("CloudSave", "No " + arrayKey + " array in JSON");
            }
        } catch (Exception e) {
            android.util.Log.e("CloudSave", "JSON parse failed: " + e.getMessage());
        }
        
        android.util.Log.i("CloudSave", "Parsed " + saves.size() + " " + category + " saves");
        return saves;
    }
    
    private void previewFilesForDownload() {
        continueWithDownload();
    }
    
    private void continueWithDownload() {
        Toast.makeText(this, "Loading files from cloud...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gamePackage = extractPackageName(selectedGame);
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/list?" +
                    "game_id=" + java.net.URLEncoder.encode(gamePackage, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8");
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    
                    // Parse backup folders from backups array with progress
                    java.util.List<String> backupFolders = new java.util.ArrayList<>();
                    java.util.List<String> backupTimestamps = new java.util.ArrayList<>();
                    
                    if (jsonResponse.contains("\"backups\":")) {
                        String[] parts = jsonResponse.split("\"folder_name\":");
                        for (int i = 1; i < parts.length; i++) {
                            String folderPart = parts[i];
                            int start = folderPart.indexOf("\"") + 1;
                            int end = folderPart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                String folderName = folderPart.substring(start, end);
                                
                                // Extract timestamp
                                String timestampPart = parts[i];
                                int tsStart = timestampPart.indexOf("\"folder_timestamp\":") + 19;
                                int tsEnd = timestampPart.indexOf(",", tsStart);
                                if (tsEnd == -1) tsEnd = timestampPart.indexOf("}", tsStart);
                                String timestamp = "";
                                if (tsStart > 18 && tsEnd > tsStart) {
                                    timestamp = timestampPart.substring(tsStart, tsEnd).trim();
                                    backupTimestamps.add(timestamp);
                                }
                                
                                // Extract progress
                                float progress = 0.0f;
                                int progStart = timestampPart.indexOf("\"progress_percentage\":");
                                if (progStart > 0) {
                                    progStart += 21;
                                    int progEnd = timestampPart.indexOf(",", progStart);
                                    if (progEnd == -1) progEnd = timestampPart.indexOf("}", progStart);
                                    if (progEnd > progStart) {
                                        try {
                                            progress = Float.parseFloat(timestampPart.substring(progStart, progEnd).trim());
                                        } catch (Exception e) {}
                                    }
                                }
                                
                                // Add folder name with progress
                                backupFolders.add(folderName + " (📊 " + progress + "%)");
                            }
                        }
                    }
                    
                    runOnUiThread(() -> {
                        if (backupFolders.isEmpty()) {
                            Toast.makeText(this, "No backups found, checking for community saves...", Toast.LENGTH_SHORT).show();
                            checkForHigherProgress("", 0.0f);
                            return;
                        }
                        
                        Toast.makeText(this, "Found " + backupFolders.size() + " backup folders", Toast.LENGTH_SHORT).show();
                        showBackupSelectionDialog(backupFolders, backupTimestamps);
                    });
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Failed to load files from cloud", Toast.LENGTH_SHORT).show();
                    });
                }
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private void showBackupSelectionDialog(java.util.List<String> backupFolders, java.util.List<String> backupTimestamps) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Backup to Download");
        
        String[] folderArray = backupFolders.toArray(new String[0]);
        
        builder.setItems(folderArray, (dialog, which) -> {
            String selectedTimestamp = backupTimestamps.get(which);
            String selectedFolder = backupFolders.get(which);
            
            // Extract progress from folder name
            float userProgress = 0.0f;
            if (selectedFolder.contains("📊 ")) {
                try {
                    String progressStr = selectedFolder.substring(selectedFolder.indexOf("📊 ") + 2);
                    progressStr = progressStr.substring(0, progressStr.indexOf("%"));
                    userProgress = Float.parseFloat(progressStr);
                } catch (Exception e) {}
            }
            
            checkForHigherProgress(selectedTimestamp, userProgress);
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void checkForHigherProgress(String selectedTimestamp, float userProgress) {
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gamePackage = extractPackageName(selectedGame);
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/community-saves?" +
                    "game_id=" + java.net.URLEncoder.encode(gamePackage, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                    "&user_progress=" + userProgress;
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    java.util.List<CommunitySave> topProgress = parseCommunitySavesFromCategory(jsonResponse, "top_progress");
                    java.util.List<CommunitySave> topLiked = parseCommunitySavesFromCategory(jsonResponse, "top_liked");
                    
                    runOnUiThread(() -> {
                        if (topProgress.isEmpty() && topLiked.isEmpty()) {
                            if (selectedTimestamp.isEmpty()) {
                                Toast.makeText(this, "No saves available for this game", Toast.LENGTH_SHORT).show();
                            } else {
                                showDownloadWarning(selectedTimestamp);
                            }
                        } else {
                            showDualCategoryDialog(selectedTimestamp, userProgress, topProgress, topLiked);
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        if (selectedTimestamp.isEmpty()) {
                            Toast.makeText(this, "No community saves found", Toast.LENGTH_SHORT).show();
                        } else {
                            showDownloadWarning(selectedTimestamp);
                        }
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (selectedTimestamp.isEmpty()) {
                        Toast.makeText(this, "Error loading community saves", Toast.LENGTH_SHORT).show();
                    } else {
                        showDownloadWarning(selectedTimestamp);
                    }
                });
            }
        }).start();
    }
    
    private void showDualCategoryDialog(String userTimestamp, float userProgress, java.util.List<CommunitySave> topProgress, java.util.List<CommunitySave> topLiked) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🌟 Community Saves");
        
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(20, 20, 20, 20);
        
        if (!topProgress.isEmpty()) {
            TextView progressHeader = new TextView(this);
            progressHeader.setText("🏆 TOP PROGRESS SAVES");
            progressHeader.setTextSize(16);
            progressHeader.setTextColor(0xFFFF6B35);
            progressHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            progressHeader.setPadding(0, 0, 0, 12);
            mainLayout.addView(progressHeader);
            
            for (int i = 0; i < topProgress.size(); i++) {
                CommunitySave save = topProgress.get(i);
                LinearLayout saveCard = createCategorySaveCard(save, i + 1, "progress");
                saveCard.setOnClickListener(v -> downloadCommunitySave(save));
                mainLayout.addView(saveCard);
            }
            
            android.view.View spacer = new android.view.View(this);
            LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 24);
            spacer.setLayoutParams(spacerParams);
            mainLayout.addView(spacer);
        }
        
        if (!topLiked.isEmpty()) {
            TextView likedHeader = new TextView(this);
            likedHeader.setText("❤️ MOST LOVED SAVES");
            likedHeader.setTextSize(16);
            likedHeader.setTextColor(0xFFE91E63);
            likedHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            likedHeader.setPadding(0, 0, 0, 12);
            mainLayout.addView(likedHeader);
            
            for (int i = 0; i < topLiked.size(); i++) {
                CommunitySave save = topLiked.get(i);
                LinearLayout saveCard = createCategorySaveCard(save, i + 1, "liked");
                saveCard.setOnClickListener(v -> downloadCommunitySave(save));
                mainLayout.addView(saveCard);
            }
        }
        
        if (!userTimestamp.isEmpty()) {
            android.view.View spacer = new android.view.View(this);
            LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16);
            spacer.setLayoutParams(spacerParams);
            mainLayout.addView(spacer);
            
            LinearLayout userCard = createUserSaveCard(userProgress);
            userCard.setOnClickListener(v -> showDownloadWarning(userTimestamp));
            mainLayout.addView(userCard);
        }
        
        scrollView.addView(mainLayout);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 800);
        scrollView.setLayoutParams(params);
        
        builder.setView(scrollView);
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void downloadSpecificBackup(String folderTimestamp) {
        Toast.makeText(this, "📁 Loading files from selected backup...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gamePackage = extractPackageName(selectedGame);
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/backup-files?" +
                    "game_id=" + java.net.URLEncoder.encode(gamePackage, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                    "&folder_timestamp=" + folderTimestamp;
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    java.util.List<String> allFiles = new java.util.ArrayList<>();
                    
                    String[] rootParts = jsonResponse.split("\"root_files\":");
                    if (rootParts.length > 1) {
                        String[] fileParts = rootParts[1].split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    String[] syncParts = jsonResponse.split("\"sync_files\":");
                    if (syncParts.length > 1) {
                        String[] fileParts = syncParts[1].split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    runOnUiThread(() -> {
                        if (allFiles.isEmpty()) {
                            Toast.makeText(this, "No files in selected backup", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        
                        showDownloadPreviewDialog(allFiles, folderTimestamp);
                    });
                } else {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Failed to load backup files", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private void showDownloadPreviewDialog(java.util.List<String> filenames, String folderTimestamp) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Files to Download (" + filenames.size() + " files)");
        
        StringBuilder fileList = new StringBuilder();
        for (String filename : filenames) {
            fileList.append("• ").append(filename).append("\n");
        }
        
        TextView textView = new TextView(this);
        textView.setText(fileList.toString());
        textView.setPadding(20, 20, 20, 20);
        textView.setTextSize(12);
        
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(textView);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 600);
        scrollView.setLayoutParams(params);
        
        builder.setView(scrollView);
        builder.setPositiveButton("Download All Files", (dialog, which) -> showDownloadWarning(folderTimestamp));
        builder.setNegativeButton("Cancel", null);
        
        builder.show();
    }

    
    private LinearLayout createCategorySaveCard(CommunitySave save, int rank, String category) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            "progress".equals(category) ? 
                new int[]{0xFFFF6B35, 0xFFF7931E} : 
                new int[]{0xFFE91E63, 0xFFAD1457});
        cardBg.setCornerRadius(16);
        card.setBackground(cardBg);
        card.setElevation(8);
        card.setPadding(20, 16, 20, 16);
        
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, 12);
        card.setLayoutParams(cardParams);
        
        LinearLayout headerLayout = new LinearLayout(this);
        headerLayout.setOrientation(LinearLayout.HORIZONTAL);
        headerLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        TextView rankText = new TextView(this);
        String rankEmoji = "progress".equals(category) ? getRankEmoji(rank) : "❤️";
        rankText.setText(rankEmoji + " #" + rank);
        rankText.setTextSize(16);
        rankText.setTextColor(0xFFFFFFFF);
        rankText.setTypeface(null, android.graphics.Typeface.BOLD);
        headerLayout.addView(rankText);
        
        android.view.View spacer = new android.view.View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 0);
        spacerParams.weight = 1;
        spacer.setLayoutParams(spacerParams);
        headerLayout.addView(spacer);
        
        // Profile image
        ImageView profileImage = new ImageView(this);
        LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(50, 50);
        imgParams.setMargins(0, 0, 10, 0);
        profileImage.setLayoutParams(imgParams);
        loadProfileImage(profileImage, save.profileImage);
        headerLayout.addView(profileImage);
        
        TextView playerText = new TextView(this);
        String displayName = save.playerName;
        if (save.isPremium) {
            displayName = "✓ " + save.playerName + " Premium";
        }
        playerText.setText(displayName);
        playerText.setTextSize(14);
        playerText.setTextColor(save.isPremium ? 0xFFFFD700 : 0xFFFFFFFF);
        playerText.setTypeface(null, android.graphics.Typeface.BOLD);
        headerLayout.addView(playerText);
        
        card.addView(headerLayout);
        
        TextView progressText = new TextView(this);
        progressText.setText("📊 Progress: " + save.progress + "%");
        progressText.setTextSize(14);
        progressText.setTextColor(0xFFE8F4FD);
        progressText.setPadding(0, 8, 0, 0);
        card.addView(progressText);
        
        LinearLayout statsLayout = new LinearLayout(this);
        statsLayout.setOrientation(LinearLayout.HORIZONTAL);
        statsLayout.setPadding(0, 12, 0, 0);
        
        TextView likesText = new TextView(this);
        likesText.setText("👍 " + save.likes);
        likesText.setTextSize(13);
        likesText.setTextColor(0xFFB8E6B8);
        likesText.setPadding(0, 0, 24, 0);
        statsLayout.addView(likesText);
        
        TextView dislikesText = new TextView(this);
        dislikesText.setText("👎 " + save.dislikes);
        dislikesText.setTextSize(13);
        dislikesText.setTextColor(0xFFFFB3B3);
        dislikesText.setPadding(0, 0, 24, 0);
        statsLayout.addView(dislikesText);
        
        TextView downloadsText = new TextView(this);
        downloadsText.setText("📥 " + save.downloads);
        downloadsText.setTextSize(13);
        downloadsText.setTextColor(0xFFFFE4B5);
        statsLayout.addView(downloadsText);
        
        card.addView(statsLayout);
        
        TextView categoryBadge = new TextView(this);
        categoryBadge.setText("progress".equals(category) ? "🏆 Top Progress" : "💖 Most Loved");
        categoryBadge.setTextSize(11);
        categoryBadge.setTextColor(0xFFD1C4E9);
        categoryBadge.setTypeface(null, android.graphics.Typeface.ITALIC);
        categoryBadge.setPadding(0, 8, 0, 0);
        card.addView(categoryBadge);
        
        return card;
    }
    
    private LinearLayout createUserSaveCard(float userProgress) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFF28A745);
        cardBg.setCornerRadius(16);
        card.setBackground(cardBg);
        card.setElevation(4);
        card.setPadding(20, 16, 20, 16);
        
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 8, 0, 0);
        card.setLayoutParams(cardParams);
        
        TextView titleText = new TextView(this);
        titleText.setText("💾 Your Save");
        titleText.setTextSize(14);
        titleText.setTextColor(0xFFFFFFFF);
        titleText.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(titleText);
        
        TextView progressText = new TextView(this);
        progressText.setText("📊 Progress: " + userProgress + "%");
        progressText.setTextSize(14);
        progressText.setTextColor(0xFFFFFFFF);
        progressText.setPadding(0, 4, 0, 4);
        card.addView(progressText);
        
        TextView hintText = new TextView(this);
        hintText.setText("👆 Tap to download your save");
        hintText.setTextSize(10);
        hintText.setTextColor(0xFFF8F9FA);
        card.addView(hintText);
        
        return card;
    }
    
    private String getRankEmoji(int rank) {
        switch (rank) {
            case 1: return "🥇";
            case 2: return "🥈";
            case 3: return "🥉";
            case 4: return "🏅";
            case 5: return "🏅";
            default: return "🌟";
        }
    }
    
    private void previewCommunityFiles(CommunitySave save) {
        Toast.makeText(this, "Loading files...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gamePackage = extractPackageName(selectedGame);
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/backup-files?" +
                    "game_id=" + java.net.URLEncoder.encode(gamePackage, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                    "&folder_timestamp=" + save.folderTimestamp;
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    java.util.List<String> allFiles = new java.util.ArrayList<>();
                    
                    String[] rootParts = jsonResponse.split("\"root_files\":");
                    if (rootParts.length > 1) {
                        String[] fileParts = rootParts[1].split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    String[] syncParts = jsonResponse.split("\"sync_files\":");
                    if (syncParts.length > 1) {
                        String[] fileParts = syncParts[1].split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    runOnUiThread(() -> {
                        if (allFiles.isEmpty()) {
                            Toast.makeText(this, "No files found", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        
                        showCommunityFilesDialog(allFiles, save);
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(this, "Failed to load files", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
    
    private void showCommunityFilesDialog(java.util.List<String> filenames, CommunitySave save) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Community Save (" + filenames.size() + " files)");
        
        StringBuilder fileList = new StringBuilder();
        fileList.append("From: ").append(save.playerName).append("\n");
        fileList.append("Progress: ").append(save.progress).append("%\n\n");
        for (String filename : filenames) {
            fileList.append("• ").append(filename).append("\n");
        }
        
        TextView textView = new TextView(this);
        textView.setText(fileList.toString());
        textView.setPadding(20, 20, 20, 20);
        textView.setTextSize(12);
        
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(textView);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 600);
        scrollView.setLayoutParams(params);
        
        builder.setView(scrollView);
        builder.setPositiveButton("📥 Download All", (dialog, which) -> showCommunityDownloadWarning(save));
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void downloadCommunitySave(CommunitySave save) {
        trackDownload(save.saveId);
        downloadHigherProgressSave(save.userId, save.folderTimestamp);
        
        android.content.SharedPreferences prefs = getSharedPreferences("cloudsave", MODE_PRIVATE);
        prefs.edit()
            .putString("last_downloaded_save_id", save.saveId)
            .putString("last_downloaded_player", save.playerName)
            .putFloat("last_downloaded_progress", save.progress)
            .putString("last_downloaded_game", selectedGame)
            .putLong("last_downloaded_time", System.currentTimeMillis())
            .apply();
    }
    
    private void trackDownload(String saveId) {
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                
                String postData = "{\"save_id\":\"" + saveId + "\"," +
                    "\"package_name\":\"" + packageName + "\"," +
                    "\"device_info\":\"" + deviceInfo + "\"}";
                
                java.net.URL url = new java.net.URL(CloudSaveConfig.getApiUrl() + "/api/saves/track-download");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                java.io.OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes());
                os.close();
                
                conn.getResponseCode();
            } catch (Exception e) {
                android.util.Log.w("DownloadSave", "Failed to track download: " + e.getMessage());
            }
        }).start();
    }
    
    private void downloadHigherProgressSave(String sourceUserId, String sourceTimestamp) {
        // Show progress overlay
        showProgressOverlay();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gamePackage = extractPackageName(selectedGame);
                
                String appName = "Unknown";
                try {
                    android.content.pm.ApplicationInfo appInfo = getPackageManager().getApplicationInfo(gamePackage, 0);
                    appName = (String) getPackageManager().getApplicationLabel(appInfo);
                } catch (Exception e) {
                    appName = gamePackage.substring(gamePackage.lastIndexOf('.') + 1);
                }
                
                String gameFolder = android.os.Environment.getExternalStorageDirectory() + "/Documents/AdultModGames/" + appName;
                java.io.File gameFolderDir = new java.io.File(gameFolder);
                
                if (gameFolderDir.exists()) {
                    deleteDirectory(gameFolderDir);
                }
                
                String savePath = gameFolder + "/saves";
                java.io.File saveDir = new java.io.File(savePath);
                java.io.File syncDir = new java.io.File(saveDir, "sync");
                saveDir.mkdirs();
                syncDir.mkdirs();
                
                String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/backup-files?" +
                    "game_id=" + java.net.URLEncoder.encode(gamePackage, "UTF-8") +
                    "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                    "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                    "&folder_timestamp=" + sourceTimestamp;
                
                java.net.URL url = new java.net.URL(urlString);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                if (conn.getResponseCode() == 200) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    String jsonResponse = response.toString();
                    java.util.List<String> allFiles = new java.util.ArrayList<>();
                    
                    String[] rootParts = jsonResponse.split("\"root_files\":");
                    if (rootParts.length > 1) {
                        String rootSection = rootParts[1];
                        int endRoot = rootSection.indexOf("],");
                        if (endRoot > 0) {
                            rootSection = rootSection.substring(0, endRoot);
                        }
                        String[] fileParts = rootSection.split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    String[] syncParts = jsonResponse.split("\"sync_files\":");
                    if (syncParts.length > 1) {
                        String syncSection = syncParts[1];
                        int endSync = syncSection.indexOf("],");
                        if (endSync > 0) {
                            syncSection = syncSection.substring(0, endSync);
                        }
                        String[] fileParts = syncSection.split("\"filename\":");
                        for (int i = 1; i < fileParts.length; i++) {
                            String filenamePart = fileParts[i];
                            int start = filenamePart.indexOf("\"") + 1;
                            int end = filenamePart.indexOf("\"", start);
                            if (start > 0 && end > start) {
                                allFiles.add(filenamePart.substring(start, end));
                            }
                        }
                    }
                    
                    final int totalFiles = allFiles.size();
                    
                    int downloadedCount = 0;
                    for (int i = 0; i < allFiles.size(); i++) {
                        String filename = allFiles.get(i);
                        final int currentFile = i + 1;
                        final int percentage = (currentFile * 100) / totalFiles;
                        
                        runOnUiThread(() -> {
                            updateProgress(percentage, "Downloading community file: " + filename, currentFile, totalFiles);
                        });
                        
                        boolean success = downloadFileFromSource(packageName, deviceInfo, filename, saveDir, sourceUserId, sourceTimestamp);
                        if (success) downloadedCount++;
                    }
                    
                    final int finalCount = downloadedCount;
                    runOnUiThread(() -> {
                        hideProgressOverlay();
                        Toast.makeText(this, "✅ Downloaded " + finalCount + " community files!", Toast.LENGTH_LONG).show();
                        // Show ad after successful community download
                        android.util.Log.d("ADS_DEBUG", "DownloadSaveActivity: Calling showRewardedAd for Community Download");
                        UnityAdsHelper.showRewardedAd(this, "Download");
                    });
                } else {
                    runOnUiThread(() -> {
                        hideProgressOverlay();
                        Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    hideProgressOverlay();
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private boolean downloadFileFromSource(String packageName, String deviceInfo, String filename, java.io.File saveDir, String sourceUserId, String sourceTimestamp) {
        try {
            String urlString = CloudSaveConfig.getApiUrl() + "/api/saves/download?" +
                "game_id=" + java.net.URLEncoder.encode(extractPackageName(selectedGame), "UTF-8") +
                "&package_name=" + java.net.URLEncoder.encode(packageName, "UTF-8") +
                "&device_info=" + java.net.URLEncoder.encode(deviceInfo, "UTF-8") +
                "&file_name=" + java.net.URLEncoder.encode(filename, "UTF-8") +
                "&source_user_id=" + java.net.URLEncoder.encode(sourceUserId, "UTF-8") +
                "&source_timestamp=" + sourceTimestamp;
            
            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            
            if (conn.getResponseCode() == 200) {
                java.io.File outputFile = new java.io.File(saveDir, filename);
                
                // Create parent directories if they don't exist
                outputFile.getParentFile().mkdirs();
                
                java.io.InputStream inputStream = conn.getInputStream();
                java.io.FileOutputStream outputStream = new java.io.FileOutputStream(outputFile);
                
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                
                inputStream.close();
                outputStream.close();
                return true;
            }
        } catch (Exception e) {
            android.util.Log.e("CloudSave", "Error downloading from source: " + e.getMessage());
        }
        return false;
    }
    
    private void showRatingDialog(String saveId, String playerName, float progress) {
        // Get additional context from SharedPreferences
        android.content.SharedPreferences prefs = getSharedPreferences("cloudsave", MODE_PRIVATE);
        String gameName = prefs.getString("last_downloaded_game", "Unknown Game");
        long downloadTime = prefs.getLong("last_downloaded_time", System.currentTimeMillis());
        
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⭐ Rate Downloaded Save");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        
        // Game info
        TextView gameInfo = new TextView(this);
        gameInfo.setText("🎮 Game: " + gameName);
        gameInfo.setTextSize(14);
        gameInfo.setTextColor(0xFF1B365D);
        gameInfo.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(gameInfo);
        
        // Download time
        TextView timeInfo = new TextView(this);
        long timeDiff = System.currentTimeMillis() - downloadTime;
        String timeAgo = getTimeAgo(timeDiff);
        timeInfo.setText("📅 Downloaded: " + timeAgo);
        timeInfo.setTextSize(12);
        timeInfo.setTextColor(0xFF6C757D);
        timeInfo.setPadding(0, 4, 0, 0);
        layout.addView(timeInfo);
        
        // Save info
        TextView saveInfo = new TextView(this);
        saveInfo.setText("👤 From: " + playerName + " (" + progress + "% progress)");
        saveInfo.setTextSize(12);
        saveInfo.setTextColor(0xFF6C757D);
        saveInfo.setPadding(0, 4, 0, 16);
        layout.addView(saveInfo);
        
        TextView infoText = new TextView(this);
        infoText.setText("How was this save?");
        infoText.setTextSize(16);
        infoText.setPadding(0, 0, 0, 20);
        layout.addView(infoText);
        
        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        Button likeButton = new Button(this);
        likeButton.setText("👍 Like");
        likeButton.setBackgroundColor(0xFF28A745);
        likeButton.setTextColor(0xFFFFFFFF);
        likeButton.setOnClickListener(v -> {
            builder.create().dismiss();
            showCommentDialog(saveId, true);
        });
        buttonLayout.addView(likeButton);
        
        Button dislikeButton = new Button(this);
        dislikeButton.setText("👎 Dislike");
        dislikeButton.setBackgroundColor(0xFFDC3545);
        dislikeButton.setTextColor(0xFFFFFFFF);
        dislikeButton.setOnClickListener(v -> {
            builder.create().dismiss();
            showCommentDialog(saveId, false);
        });
        buttonLayout.addView(dislikeButton);
        
        layout.addView(buttonLayout);
        builder.setView(layout);
        builder.setNegativeButton("Skip", (dialog, which) -> {
            clearPendingRating();
        });
        builder.setCancelable(false);
        builder.show();
    }
    
    private void showCommentDialog(String saveId, boolean isLike) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("💬 Add Comment (Optional)");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        
        TextView hintText = new TextView(this);
        hintText.setText("Share your thoughts (max 50 words):");
        hintText.setTextSize(14);
        hintText.setPadding(0, 0, 0, 10);
        layout.addView(hintText);
        
        android.widget.EditText commentEdit = new android.widget.EditText(this);
        commentEdit.setHint("Great save! All scenes unlocked...");
        commentEdit.setMaxLines(3);
        layout.addView(commentEdit);
        
        builder.setView(layout);
        builder.setPositiveButton("Submit", (dialog, which) -> {
            String comment = commentEdit.getText().toString().trim();
            submitRatingAndContinue(saveId, isLike, comment);
        });
        builder.setNegativeButton("Skip Comment", (dialog, which) -> {
            submitRatingAndContinue(saveId, isLike, "");
        });
        builder.setCancelable(false);
        builder.show();
    }
    
    private void submitRatingAndContinue(String saveId, boolean isLike, String comment) {
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                
                String postData = "{\"save_id\":\"" + saveId + "\"," +
                    "\"is_like\":" + isLike + "," +
                    "\"comment\":\"" + comment.replace("\"", "\\\"") + "\"," +
                    "\"package_name\":\"" + packageName + "\"," +
                    "\"device_info\":\"" + deviceInfo + "\"}";
                
                java.net.URL url = new java.net.URL(CloudSaveConfig.getApiUrl() + "/api/saves/rate");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                java.io.OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes());
                os.close();
                
                int responseCode = conn.getResponseCode();
                
                runOnUiThread(() -> {
                    if (responseCode == 200) {
                        Toast.makeText(this, "✅ Rating submitted! Thank you for helping the community.", Toast.LENGTH_LONG).show();
                        
                        // Store in rating history
                        storeRatingHistory(saveId, isLike, comment);
                    } else {
                        Toast.makeText(this, "Rating submitted", Toast.LENGTH_SHORT).show();
                    }
                    
                    clearPendingRating();
                });
            } catch (Exception e) {
                android.util.Log.w("DownloadSave", "Failed to submit rating: " + e.getMessage());
                runOnUiThread(() -> {
                    clearPendingRating();
                });
            }
        }).start();
    }
    
    private void clearPendingRating() {
        android.content.SharedPreferences prefs = getSharedPreferences("cloudsave", MODE_PRIVATE);
        prefs.edit()
            .remove("last_downloaded_save_id")
            .remove("last_downloaded_player")
            .remove("last_downloaded_progress")
            .remove("last_downloaded_game")
            .remove("last_downloaded_time")
            .apply();
    }
    
    private void openMyActivity() {
        Intent intent = new Intent(this, MyRatingActivity.class);
        startActivity(intent);
    }
    
    private String getTimeAgo(long timeDiff) {
        long seconds = timeDiff / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        if (days > 0) {
            return days + " day" + (days > 1 ? "s" : "") + " ago";
        } else if (hours > 0) {
            return hours + " hour" + (hours > 1 ? "s" : "") + " ago";
        } else if (minutes > 0) {
            return minutes + " minute" + (minutes > 1 ? "s" : "") + " ago";
        } else {
            return "Just now";
        }
    }
    
    private void storeRatingHistory(String saveId, boolean isLike, String comment) {
        android.content.SharedPreferences prefs = getSharedPreferences("cloudsave", MODE_PRIVATE);
        String gameName = prefs.getString("last_downloaded_game", "Unknown Game");
        String playerName = prefs.getString("last_downloaded_player", "Unknown Player");
        float progress = prefs.getFloat("last_downloaded_progress", 0.0f);
        long downloadTime = prefs.getLong("last_downloaded_time", System.currentTimeMillis());
        
        // Get existing history
        String existingHistory = prefs.getString("rating_history", "");
        
        // Create new entry
        String newEntry = saveId + "|" + gameName + "|" + playerName + "|" + progress + "|" + 
                         downloadTime + "|" + isLike + "|" + comment.replace("|", "") + "|" + System.currentTimeMillis();
        
        // Add to history (keep last 20 entries)
        String updatedHistory;
        if (existingHistory.isEmpty()) {
            updatedHistory = newEntry;
        } else {
            String[] entries = existingHistory.split("\\n");
            StringBuilder sb = new StringBuilder();
            sb.append(newEntry).append("\n");
            
            // Keep last 19 entries
            for (int i = 0; i < Math.min(19, entries.length); i++) {
                sb.append(entries[i]).append("\n");
            }
            updatedHistory = sb.toString();
        }
        
        prefs.edit().putString("rating_history", updatedHistory).apply();
    }
    
    private void storeRatingInActivity(String saveId, boolean isLike, String comment) {
        android.content.SharedPreferences prefs = getSharedPreferences("cloudsave", MODE_PRIVATE);
        String existingHistory = prefs.getString("rating_history", "");
        
        // Create entry for community rating
        String newEntry = saveId + "|Community Save|Community Player|0.0|" + 
                         System.currentTimeMillis() + "|" + isLike + "|" + comment.replace("|", "") + "|" + System.currentTimeMillis();
        
        String updatedHistory;
        if (existingHistory.isEmpty()) {
            updatedHistory = newEntry;
        } else {
            String[] entries = existingHistory.split("\\n");
            StringBuilder sb = new StringBuilder();
            sb.append(newEntry).append("\n");
            
            for (int i = 0; i < Math.min(19, entries.length); i++) {
                sb.append(entries[i]).append("\n");
            }
            updatedHistory = sb.toString();
        }
        
        prefs.edit().putString("rating_history", updatedHistory).apply();
    }
    
    private boolean isBackupInTop10(String timestamp, float progress) {
        // Find the backup by timestamp and return its top10 status from API
        for (int i = 0; i < uploadedFolders.size(); i++) {
            String folderName = uploadedFolders.get(i);
            String folderTimestamp = folderName.substring(folderName.lastIndexOf("_") + 1);
            if (folderTimestamp.equals(timestamp)) {
                return i < folderTop10Status.size() ? folderTop10Status.get(i) : false;
            }
        }
        return false;
    }
    
    private boolean isBackupShared(String timestamp) {
        // Find the backup by timestamp and return its shared status from API
        for (int i = 0; i < uploadedFolders.size(); i++) {
            String folderName = uploadedFolders.get(i);
            String folderTimestamp = folderName.substring(folderName.lastIndexOf("_") + 1);
            if (folderTimestamp.equals(timestamp)) {
                return i < folderSharedStatus.size() ? folderSharedStatus.get(i) : false;
            }
        }
        return false;
    }
    
    private void showMagicalCelebration(String timestamp, float progress) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("🎆 MAGICAL CELEBRATION! 🎆");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        android.graphics.drawable.GradientDrawable magicalBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFFFF6B35, 0xFFFF1493, 0xFF9932CC, 0xFF4169E1});
        magicalBg.setCornerRadius(20);
        layout.setBackground(magicalBg);
        
        TextView celebration = new TextView(this);
        celebration.setText("🎉🎆🌟 CONGRATULATIONS! 🌟🎆🎉\n\n🏆 You're in the TOP 10! 🏆\n\n🌈 Your save is LEGENDARY! 🌈\n\n🚀 Progress: " + progress + "% 🚀\n\n🎆 Share your achievement! 🎆\n\n✨ You're inspiring others! ✨");
        celebration.setTextSize(16);
        celebration.setTextColor(0xFFFFFFFF);
        celebration.setTypeface(null, android.graphics.Typeface.BOLD);
        celebration.setGravity(android.view.Gravity.CENTER);
        celebration.setPadding(20, 20, 20, 20);
        layout.addView(celebration);
        
        // Magical buttons
        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setPadding(0, 20, 0, 0);
        
        Button shareButton = new Button(this);
        shareButton.setText("📢 Share Joy");
        shareButton.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable shareBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFF32CD32, 0xFF00FF00});
        shareBg.setCornerRadius(25);
        shareButton.setBackground(shareBg);
        shareButton.setElevation(8);
        shareButton.setOnClickListener(v -> {
            shareAchievement(progress);
            builder.create().dismiss();
        });
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        shareParams.setMargins(0, 0, 10, 0);
        shareButton.setLayoutParams(shareParams);
        
        Button sparkleButton = new Button(this);
        sparkleButton.setText("✨ More Magic");
        sparkleButton.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable sparkleBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{0xFFFFD700, 0xFFFFA500});
        sparkleBg.setCornerRadius(25);
        sparkleButton.setBackground(sparkleBg);
        sparkleButton.setElevation(8);
        sparkleButton.setOnClickListener(v -> {
            showMoreMagic();
            builder.create().dismiss();
        });
        LinearLayout.LayoutParams sparkleParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        sparkleParams.setMargins(10, 0, 0, 0);
        sparkleButton.setLayoutParams(sparkleParams);
        
        buttonLayout.addView(shareButton);
        buttonLayout.addView(sparkleButton);
        layout.addView(buttonLayout);
        
        builder.setView(layout);
        builder.setPositiveButton("🎆 Amazing!", (dialog, which) -> {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) vibrator.vibrate(500);
            Toast.makeText(this, "🎉 You're absolutely LEGENDARY! 🎉", Toast.LENGTH_LONG).show();
        });
        builder.setCancelable(true);
        
        android.app.AlertDialog dialog = builder.create();
        dialog.show();
        
        // Add magical animation effect
        android.view.animation.Animation pulse = new android.view.animation.ScaleAnimation(
            1.0f, 1.1f, 1.0f, 1.1f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        pulse.setDuration(1000);
        pulse.setRepeatCount(android.view.animation.Animation.INFINITE);
        pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
        layout.startAnimation(pulse);
    }
    
    private void shareAchievement(float progress) {
        try {
            android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, 
                "🎆 I'm in the TOP 10 community saves! 🎆\n\n" +
                "🏆 Achievement: " + progress + "% game progress\n" +
                "🌟 My save is helping other players!\n" +
                "🚀 Join the community and share your progress too!\n\n" +
                "#Top10Player #CommunityHero #GameProgress");
            startActivity(android.content.Intent.createChooser(shareIntent, "Share your achievement!"));
        } catch (Exception e) {
            Toast.makeText(this, "🎉 You're still amazing even if sharing failed! 🎉", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void shareBackupWithCommunity(String timestamp) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("🌍 Share with Community");
        builder.setMessage("Share this save with the community to help other players?\n\n⚠️ Once shared, this save cannot be deleted if it becomes popular (top 10).");
        builder.setPositiveButton("Share", (dialog, which) -> {
            performShareBackup(timestamp);
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void performShareBackup(String timestamp) {
        Toast.makeText(this, "Sharing with community...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gameId = extractPackageName(selectedGame);
                
                String postData = "{\"game_id\":\"" + gameId + "\"," +
                    "\"folder_timestamp\":\"" + timestamp + "\"," +
                    "\"package_name\":\"" + packageName + "\"," +
                    "\"device_info\":\"" + deviceInfo + "\"}";
                
                java.net.URL url = new java.net.URL(CloudSaveConfig.getApiUrl() + "/api/saves/share-backup");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                java.io.OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes());
                os.close();
                
                int responseCode = conn.getResponseCode();
                
                runOnUiThread(() -> {
                    if (responseCode == 200) {
                        Toast.makeText(this, "✅ Backup shared with community!", Toast.LENGTH_LONG).show();
                        // Refresh the folder list to update button states
                        folderListLayout.removeAllViews();
                        loadUploadedFolders();
                    } else {
                        Toast.makeText(this, "Failed to share backup", Toast.LENGTH_SHORT).show();
                    }
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
    
    private void showAllTop10Celebration(int top10Count) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("🌟 ULTIMATE LEGEND! 🌟");
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        android.graphics.drawable.GradientDrawable magicalBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFFFFD700, 0xFFFF6B35, 0xFFFF1493, 0xFF9932CC});
        magicalBg.setCornerRadius(20);
        layout.setBackground(magicalBg);
        
        TextView celebration = new TextView(this);
        celebration.setText("🎆🏆 INCREDIBLE ACHIEVEMENT! 🏆🎆\n\n🌟 ALL YOUR SAVES ARE IN TOP 10! 🌟\n\n🚀 You have " + top10Count + " LEGENDARY saves! 🚀\n\n👑 You're the ULTIMATE PLAYER! 👑\n\n🎉 All your saves are now FREE! 🎉\n\n✨ You're inspiring the entire community! ✨");
        celebration.setTextSize(16);
        celebration.setTextColor(0xFFFFFFFF);
        celebration.setTypeface(null, android.graphics.Typeface.BOLD);
        celebration.setGravity(android.view.Gravity.CENTER);
        celebration.setPadding(20, 20, 20, 20);
        layout.addView(celebration);
        
        builder.setView(layout);
        builder.setPositiveButton("🎆 I'M LEGENDARY!", (dialog, which) -> {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null) {
                long[] pattern = {0, 300, 100, 300, 100, 300, 100, 800};
                vibrator.vibrate(pattern, -1);
            }
            Toast.makeText(this, "🌟 You're absolutely LEGENDARY! All saves FREE! 🌟", Toast.LENGTH_LONG).show();
        });
        builder.setCancelable(true);
        builder.show();
    }
    
    private void showDownloadWarning(String folderTimestamp) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚠️ Warning");
        builder.setMessage("This will REMOVE your existing game folder and replace it with the backup.\n\nAll current saves, screenshots, and other files will be DELETED.\n\nContinue?");
        builder.setPositiveButton("Yes, Replace", (dialog, which) -> downloadCloudBackup(folderTimestamp));
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void showCommunityDownloadWarning(CommunitySave save) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚠️ Warning");
        builder.setMessage("This will REMOVE your existing game folder and replace it with the community backup.\n\nAll current saves, screenshots, and other files will be DELETED.\n\nContinue?");
        builder.setPositiveButton("Yes, Replace", (dialog, which) -> downloadCommunitySave(save));
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    private void showMoreMagic() {
        Toast.makeText(this, "✨🎆🌈 SPARKLE SPARKLE! You're absolutely MAGICAL! 🌈🎆✨", Toast.LENGTH_LONG).show();
        
        // Vibration pattern for magical effect
        android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null) {
            long[] pattern = {0, 200, 100, 200, 100, 500};
            vibrator.vibrate(pattern, -1);
        }
        
        // Show floating magical message
        final TextView magicText = new TextView(this);
        magicText.setText("✨ LEGENDARY PLAYER ✨");
        magicText.setTextSize(20);
        magicText.setTextColor(0xFFFFD700);
        magicText.setTypeface(null, android.graphics.Typeface.BOLD);
        magicText.setGravity(android.view.Gravity.CENTER);
        magicText.setBackgroundColor(0x88000000);
        magicText.setPadding(20, 10, 20, 10);
        
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = android.view.Gravity.CENTER;
        
        ((android.widget.FrameLayout) findViewById(android.R.id.content)).addView(magicText, params);
        
        // Animate and remove after 3 seconds
        android.view.animation.Animation fadeIn = new android.view.animation.AlphaAnimation(0, 1);
        fadeIn.setDuration(500);
        magicText.startAnimation(fadeIn);
        
        new android.os.Handler().postDelayed(() -> {
            android.view.animation.Animation fadeOut = new android.view.animation.AlphaAnimation(1, 0);
            fadeOut.setDuration(500);
            magicText.startAnimation(fadeOut);
            new android.os.Handler().postDelayed(() -> {
                ((android.widget.FrameLayout) findViewById(android.R.id.content)).removeView(magicText);
            }, 500);
        }, 2500);
    }
    
    @Override
    public void onBackPressed() {
        if (showingFolders) {
            // Go back to games list
            showingFolders = false;
            gamesContainer.setVisibility(LinearLayout.VISIBLE);
            foldersContainer.setVisibility(LinearLayout.GONE);
            
            // Update title - get from title container
            LinearLayout contentLayout = (LinearLayout) gamesContainer.getParent();
            LinearLayout titleContainer = (LinearLayout) contentLayout.getChildAt(0);
            TextView title = (TextView) titleContainer.getChildAt(0);
            title.setText("📥 Select Game to Download To");
            
            // Remove My Activity button if it exists
            if (titleContainer.getChildCount() > 1) {
                titleContainer.removeViewAt(1);
            }
            
            // Clear folder data
            folderListLayout.removeAllViews();
            uploadedFolders.clear();
            folderProgress.clear();
            folderTop10Status.clear();
            folderSharedStatus.clear();
        } else {
            super.onBackPressed();
        }
    }
    
    private void showProgressOverlay() {
        if (progressOverlay != null) return;
        
        // Create overlay layout
        progressOverlay = new LinearLayout(this);
        progressOverlay.setOrientation(LinearLayout.VERTICAL);
        progressOverlay.setBackgroundColor(0xE6000000); // Semi-transparent black
        progressOverlay.setGravity(android.view.Gravity.CENTER);
        progressOverlay.setPadding(40, 40, 40, 40);
        
        // Progress card
        LinearLayout progressCard = new LinearLayout(this);
        progressCard.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF1B365D, 0xFF6C757D});
        cardBg.setCornerRadius(20);
        progressCard.setBackground(cardBg);
        progressCard.setElevation(12);
        progressCard.setPadding(40, 40, 40, 40);
        
        // Title
        progressTitle = new TextView(this);
        progressTitle.setText("📥 Downloading Files");
        progressTitle.setTextSize(20);
        progressTitle.setTextColor(0xFFFFFFFF);
        progressTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        progressTitle.setGravity(android.view.Gravity.CENTER);
        progressTitle.setPadding(0, 0, 0, 20);
        progressCard.addView(progressTitle);
        
        // Percentage text
        progressPercentage = new TextView(this);
        progressPercentage.setText("0%");
        progressPercentage.setTextSize(48);
        progressPercentage.setTextColor(0xFFFFD700);
        progressPercentage.setTypeface(null, android.graphics.Typeface.BOLD);
        progressPercentage.setGravity(android.view.Gravity.CENTER);
        progressPercentage.setPadding(0, 0, 0, 20);
        progressCard.addView(progressPercentage);
        
        // Progress bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
            400, 20);
        barParams.setMargins(0, 0, 0, 20);
        progressBar.setLayoutParams(barParams);
        progressCard.addView(progressBar);
        
        // Progress text
        progressText = new TextView(this);
        progressText.setText("Preparing download...");
        progressText.setTextSize(14);
        progressText.setTextColor(0xFFE8F4FD);
        progressText.setGravity(android.view.Gravity.CENTER);
        progressText.setMaxLines(2);
        progressCard.addView(progressText);
        
        progressOverlay.addView(progressCard);
        
        // Add to root view
        android.widget.FrameLayout rootView = (android.widget.FrameLayout) findViewById(android.R.id.content);
        android.widget.FrameLayout.LayoutParams overlayParams = new android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        rootView.addView(progressOverlay, overlayParams);
    }
    
    private void updateProgress(int percentage, String message, int current, int total) {
        if (progressOverlay == null) return;
        
        progressPercentage.setText(percentage + "%");
        progressBar.setProgress(percentage);
        progressText.setText(message + "\n(" + current + "/" + total + " files)");
    }
    
    private void hideProgressOverlay() {
        if (progressOverlay != null) {
            android.widget.FrameLayout rootView = (android.widget.FrameLayout) findViewById(android.R.id.content);
            rootView.removeView(progressOverlay);
            progressOverlay = null;
            progressTitle = null;
            progressText = null;
            progressBar = null;
            progressPercentage = null;
        }
    }
    
    private void showRefreshAdDialog(String timestamp) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("🔄 Refresh Backup");
        builder.setMessage("You are going to see an ad because you need refresh and this will help you to extend your backup and helps us to save your backup genuinely you are interested in it.");
        builder.setPositiveButton("Agree", (dialog, which) -> {
            pendingRefreshTimestamp = timestamp;
            UnityAdsHelper.showRewardedAd(this, "Refresh");
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    public void performRefreshBackup(String timestamp) {
        Toast.makeText(this, "Refreshing backup...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                String packageName = getPackageName();
                String deviceInfo = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                String gameId = extractPackageName(selectedGame);
                
                String postData = "{\"game_id\":\"" + gameId + "\"," +
                    "\"folder_timestamp\":\"" + timestamp + "\"," +
                    "\"package_name\":\"" + packageName + "\"," +
                    "\"device_info\":\"" + deviceInfo + "\"}";
                
                java.net.URL url = new java.net.URL(CloudSaveConfig.getApiUrl() + "/api/saves/refresh-backup");
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                
                java.io.OutputStream os = conn.getOutputStream();
                os.write(postData.getBytes());
                os.close();
                
                int responseCode = conn.getResponseCode();
                
                runOnUiThread(() -> {
                    if (responseCode == 200) {
                        Toast.makeText(this, "✅ Backup refreshed! Extended for 1 month.", Toast.LENGTH_LONG).show();
                        // Refresh the folder list
                        folderListLayout.removeAllViews();
                        loadUploadedFolders();
                    } else {
                        Toast.makeText(this, "Failed to refresh backup", Toast.LENGTH_SHORT).show();
                    }
                });
                
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
