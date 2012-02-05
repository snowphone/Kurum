package kr.iamghost.kurum;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

import org.eclipse.swt.widgets.Display;

public class AppSyncr implements ProcessWatcherListener {
	private final static int PROCESS_WATCH_PERIOD = 1000 * 5;
	private final static int AUTOMATIC_SYNC_PERIOD = 1000 * 60 * 5;
	private HashMap<String, AppConfig> appConfigs;
	private PropertyUtil kurumConfig;
	private ProcessWatcher processWatcher;
	private DropboxUtil dropbox;
	private Timer autoSyncTimer;
	
	public AppSyncr() {
		dropbox = new DropboxUtil();
		if (!dropbox.isLinked()) Global.set("DropboxLoginError", true);
	}
	
	
	public void syncAllApps() {
		if (dropbox.isLinked() == true)
		{
			for (final AppConfig app : appConfigs.values()) {
				if (app.checkAllVars())
					new Thread() {
						public void run() {
							syncApp(app, false);
						}}.start();
			}
		}
	}
	
	public void timedSync() {
		if (!processWatcher.foundAtLeastOneProcess())
			syncAllApps();
	}
	
	public void init() {
		reload();
		kurumConfig = PropertyUtil.getDefaultProperty();
		startTimedSync();
		syncAllApps();
	}
	
	public void reload() {
		refreshAppConfigs();
		refreshProcessWatcher();
	}
	
	public void startTimedSync() {
		autoSyncTimer = new Timer();
		autoSyncTimer.scheduleAtFixedRate(new TimerTask() {
			
			@Override
			public void run() {
				timedSync();
			}
		}, AUTOMATIC_SYNC_PERIOD, AUTOMATIC_SYNC_PERIOD);
	}
	
	public void refreshProcessWatcher() {
		processWatcher = new ProcessWatcher();
		
		for (AppConfig app : appConfigs.values()) {
			processWatcher.addProcess(app.getProcessName(), this);
		}
		processWatcher.start(PROCESS_WATCH_PERIOD);
	}
	
	public void refreshAppConfigs() {
		appConfigs = new HashMap<String, AppConfig>();
		AppConfigParser configParser = new AppConfigParser();
		
		File file = new File(Environment.parsePath("%AppData%/Kurum/AppConfigs"));
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			
			for (File singleFile : files) {
				AppConfig tempConfig;
				configParser.parse(singleFile.getAbsolutePath());
				tempConfig = configParser.getAppConfig();
				if (tempConfig != null)
				{
					appConfigs.put(tempConfig.getProcessName(), tempConfig);
				}
					
			}
		}
	}
	
	@Override
	public void onProcessDisappeared(ProcessWatcherEvent e) {
		syncApp(appConfigs.get(e.getProcessName()), true);
	}
	
	public void uploadToDropbox(AppConfig config) {
		Iterator<AppConfigFileEntry> it = config.getFilesIterator();
		
		String appName = config.getAppName();
	
		File tempZipFile = null;
		
		try {
			tempZipFile = File.createTempFile(appName, ".zip");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if (tempZipFile != null) {
			ZipUtil newZip = new ZipUtil().createZip(tempZipFile);
			
			while (it.hasNext()) {
				AppConfigFileEntry fileInfo = it.next();
				newZip.add(fileInfo);
			}
			
			newZip.save();

			DropboxEntry upload = dropbox.upload(new File(tempZipFile.getAbsolutePath()),
					config.getDropboxZipPath(), true);
			
			saveSyncInfo(upload);
			
			showTooltip(Language.getFormattedString("UploadFinished", config.getAppName()));
		}
	}
	
	public void downloadToLocal(AppConfig config) {
		Iterator<AppConfigFileEntry> it = config.getFilesIterator();
		
		String appName = config.getAppName();

		File tempFile = null;
		File tempDirectory = null;
		ZipUtil zip = null;
		
		try {
			tempFile = File.createTempFile(appName, ".zip");
			
			dropbox.download(config.getDropboxZipPath(), tempFile);
			zip = new ZipUtil().loadZip(tempFile);

		}
		catch (Exception e) {
			e.printStackTrace();
		}
		
		while (it.hasNext()) {
			AppConfigFileEntry fileInfo = it.next();
			
			try {
				tempDirectory = File.createTempFile("Kurum", "");
				tempDirectory.delete();
				tempDirectory.deleteOnExit();
				
				zip.extract(tempDirectory);
				
				String tempPath = tempDirectory.getAbsolutePath() + "/" + fileInfo.getDropboxPath();
				
				if (fileInfo.isNeedCleanup())
					FileUtil.delete(fileInfo.getOriginalFile());
				
				FileUtil.copy(new File(tempPath), fileInfo.getOriginalFile());
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
		}
		
		FileUtil.delete(tempFile);
		FileUtil.delete(tempDirectory);
		
		DropboxEntry meta = dropbox.getMetadata(config.getDropboxZipPath());
		saveSyncInfo(meta);
		
		showTooltip(Language.getFormattedString("DownloadFinished", config.getAppName()));
	}
	
	public void showTooltip(final String text) {
		Display.getDefault().asyncExec(new Runnable() {
			
			@Override
			public void run() {
				Global.set("ShowToolTip", text);
			}
		});
		
	}
	public void saveSyncInfo(DropboxEntry entry) {
		kurumConfig.setString(entry.fileName, entry.modifydate.toString());
	}

	public void syncApp(AppConfig config, boolean force) {
		if (config != null) {
			String appName = config.getAppName();
			String archivePath = config.getDropboxZipPath();
			Date localDate = Util.stringToDate(kurumConfig.getString(appName + ".zip"));
			
			DropboxEntry lastSync = dropbox.getMetadata(archivePath);
			
			if (!lastSync.isValid || lastSync.isDeleted) {
				//First upload->Upload
				Log.write("First upload :" + config.getAppTitle());
				uploadToDropbox(config);
			}
			
			else if(lastSync.isValid) {
				if (!lastSync.isDeleted && localDate == null) {
					//First sync->Download
					Log.write("First sync :" + config.getAppTitle());
					downloadToLocal(config);
				}
				else if(lastSync.modifydate.after(localDate)) {
					//Dropbox is newer->Download
					Log.write("Dropbox is newer :" + config.getAppTitle());
					downloadToLocal(config);
				}
				else if(lastSync.modifydate.before(localDate) && !force)
				{
					Log.write("Local is newer :" + config.getAppTitle());
					//Local is (maybe) newer
					uploadToDropbox(config);
				}
				else if (force)
				{
					Log.write("Forcing upload :" + config.getAppTitle());
					uploadToDropbox(config);
				}
				else
				{
					//Same, but will never reach here except timed sync
					Log.write("Same, POMF =3 :" + config.getAppTitle());
				}
			}
		}
		kurumConfig.save();
	}
}
