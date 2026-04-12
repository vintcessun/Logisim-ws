/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.gui.start;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
//import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.JOptionPane;

import com.cburch.logisim.LogisimVersion;
import com.cburch.logisim.Main;
import com.cburch.logisim.file.LoadFailedException;
import com.cburch.logisim.file.Loader;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.gui.main.Print;
import com.cburch.logisim.gui.menu.LogisimMenuBar;
import com.cburch.logisim.gui.menu.ProjectLibraryActions;
import com.cburch.logisim.gui.menu.WindowManagers;
import com.cburch.logisim.plugin.PluginFolder;
import com.cburch.logisim.plugin.PluginPreferences;
import com.cburch.logisim.plugin.PluginUtils;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.proj.ProjectActions;
import com.cburch.logisim.util.ArgonXML;
import com.cburch.logisim.util.LocaleManager;
import com.cburch.logisim.util.MacCompatibility;
import com.cburch.logisim.util.StringUtil;

public class Startup {
	private static Startup startupTemp = null;

	public static void AsktoSave(Frame frame) {
		frame.confirmClose();
	}

	static void doOpen(File file) {
		if (startupTemp != null)
			startupTemp.doOpenFile(file);
	}

	static void doPrint(File file) {
		if (startupTemp != null)
			startupTemp.doPrintFile(file);
	}

	private static String getFileExtension(File file) {
		String fileName = file.getName();
		if (fileName.lastIndexOf(".") != -1 && fileName.lastIndexOf(".") != 0)
			return fileName.substring(fileName.lastIndexOf("."));
		else
			return "";
	}

	public static String getFilePath() {
		try {
			return URLDecoder.decode(
					new File(Startup.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath())
							.getAbsolutePath(),
					"UTF-8");
		} catch (Exception e) {
			return "";
		}
	}

	public static String getFolderPath() {
		try {
			return URLDecoder.decode(
					new File(Startup.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath())
							.getParentFile().getAbsolutePath(),
					"UTF-8");
		} catch (Exception e) {
			return "";
		}
	}

	public static Startup parseArgs(String[] args) {
		// see whether we'll be using any graphics

		boolean isTty = false;
		boolean isClearPreferences = false;
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-tty")) {
				isTty = true;
			} else if (args[i].equals("-clearprefs") || args[i].equals("-clearprops")) {
				isClearPreferences = true;
			}
		}

		if (!isTty) {
			// we're using the GUI: Set up the Look&Feel to match the platform
			System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Logisim");
			System.setProperty("apple.laf.useScreenMenuBar", "true");

			LocaleManager.setReplaceAccents(false);
			AppPreferences.handleGraphicsAcceleration();
			AppPreferences.setLayout();
		}

		Startup ret = new Startup(isTty);
		startupTemp = ret;
		if (!isTty) {
			registerHandler();
		}

		if (isClearPreferences) {
			AppPreferences.clear();
		}
		// parse arguments
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg.equals("-tty")) {
				if (i + 1 < args.length) {
					i++;
					String[] fmts = args[i].split(",");
					if (fmts.length == 0) {
						System.err.println(Strings.get("ttyFormatError")); // OK
					}
					for (int j = 0; j < fmts.length; j++) {
						String fmt = fmts[j].trim();
						if (fmt.equals("table")) {
							ret.ttyFormat |= TtyInterface.FORMAT_TABLE;
						} else if (fmt.equals("speed")) {
							ret.ttyFormat |= TtyInterface.FORMAT_SPEED;
						} else if (fmt.equals("tty")) {
							ret.ttyFormat |= TtyInterface.FORMAT_TTY;
						} else if (fmt.equals("halt")) {
							ret.ttyFormat |= TtyInterface.FORMAT_HALT;
						} else if (fmt.equals("stats")) {
							ret.ttyFormat |= TtyInterface.FORMAT_STATISTICS;
						} else {
							System.err.println(Strings.get("ttyFormatError")); // OK
						}
					}
				} else {
					System.err.println(Strings.get("ttyFormatError")); // OK
					return null;
				}
			} else if (arg.equals("-sub")) {
				if (i + 2 < args.length) {
					File a = new File(args[i + 1]);
					File b = new File(args[i + 2]);
					if (ret.substitutions.containsKey(a)) {
						System.err.println(Strings.get("argDuplicateSubstitutionError")); // OK
						return null;
					} else {
						ret.substitutions.put(a, b);
						i += 2;
					}
				} else {
					System.err.println(Strings.get("argTwoSubstitutionError")); // OK
					return null;
				}
			} else if (arg.equals("-load")) {
				if (i + 1 < args.length) {
					i++;
					if (ret.loadFile != null) {
						System.err.println(Strings.get("loadMultipleError")); // OK
					}
					File f = new File(args[i]);
					ret.loadFile = f;
				} else {
					System.err.println(Strings.get("loadNeedsFileError")); // OK
					return null;
				}
			} else if (arg.equals("-empty")) {
				if (ret.templFile != null || ret.templEmpty || ret.templPlain) {
					System.err.println(Strings.get("argOneTemplateError")); // OK
					return null;
				}
				ret.templEmpty = true;
			} else if (arg.equals("-plain")) {
				if (ret.templFile != null || ret.templEmpty || ret.templPlain) {
					System.err.println(Strings.get("argOneTemplateError")); // OK
					return null;
				}
				ret.templPlain = true;
			} else if (arg.equals("-version")) {
				System.out.println(Main.VERSION_NAME); // OK
				return null;
			} else if (arg.equals("-gates")) {
				i++;
				if (i >= args.length)
					printUsage();
				String a = args[i];
				if (a.equals("shaped")) {
					AppPreferences.GATE_SHAPE.set(AppPreferences.SHAPE_SHAPED);
				} else if (a.equals("rectangular")) {
					AppPreferences.GATE_SHAPE.set(AppPreferences.SHAPE_RECTANGULAR);
				} else {
					System.err.println(Strings.get("argGatesOptionError")); // OK
					System.exit(-1);
				}
			} else if (arg.equals("-locale")) {
				i++;
				if (i >= args.length)
					printUsage();
				setLocale(args[i]);
			} else if (arg.equals("-accents")) {
				i++;
				if (i >= args.length)
					printUsage();
				String a = args[i];
				if (a.equals("yes")) {
					AppPreferences.ACCENTS_REPLACE.setBoolean(false);
				} else if (a.equals("no")) {
					AppPreferences.ACCENTS_REPLACE.setBoolean(true);
				} else {
					System.err.println(Strings.get("argAccentsOptionError")); // OK
					System.exit(-1);
				}
			} else if (arg.equals("-template")) {
				if (ret.templFile != null || ret.templEmpty || ret.templPlain) {
					System.err.println(Strings.get("argOneTemplateError")); // OK
					return null;
				}
				i++;
				if (i >= args.length)
					printUsage();
				ret.templFile = new File(args[i]);
				if (!ret.templFile.exists()) {
					System.err.println(StringUtil.format( // OK
							Strings.get("templateMissingError"), args[i]));
				} else if (!ret.templFile.canRead()) {
					System.err.println(StringUtil.format( // OK
							Strings.get("templateCannotReadError"), args[i]));
				}
			} else if (arg.equals("-nosplash")) {
				ret.showSplash = false;
			} else if (arg.equals("-clearprefs")) {
				// already handled above
			} else if (arg.charAt(0) == '-') {
				printUsage();
				return null;
			} else {
				ret.filesToOpen.add(new File(arg));
			}
		}
		if (ret.isTty && ret.filesToOpen.isEmpty()) {
			System.err.println(Strings.get("ttyNeedsFileError")); // OK
			return null;
		}
		if (ret.loadFile != null && !ret.isTty) {
			System.err.println(Strings.get("loadNeedsTtyError")); // OK
			return null;
		}
		return ret;
	}

	private static void printUsage() {
		System.err.println(StringUtil.format(Strings.get("argUsage"), Startup.class.getName())); // OK
		System.err.println(); // OK
		System.err.println(Strings.get("argOptionHeader")); // OK
		System.err.println("   " + Strings.get("argAccentsOption")); // OK
		System.err.println("   " + Strings.get("argClearOption")); // OK
		System.err.println("   " + Strings.get("argEmptyOption")); // OK
		System.err.println("   " + Strings.get("argGatesOption")); // OK
		System.err.println("   " + Strings.get("argHelpOption")); // OK
		System.err.println("   " + Strings.get("argLoadOption")); // OK
		System.err.println("   " + Strings.get("argLocaleOption")); // OK
		System.err.println("   " + Strings.get("argNoSplashOption")); // OK
		System.err.println("   " + Strings.get("argPlainOption")); // OK
		System.err.println("   " + Strings.get("argSubOption")); // OK
		System.err.println("   " + Strings.get("argTemplateOption")); // OK
		System.err.println("   " + Strings.get("argTtyOption")); // OK
		System.err.println("   " + Strings.get("argVersionOption")); // OK
		System.exit(-1);
	}

	private static void registerHandler() {
		try {
			Class<?> needed1 = Class.forName("com.apple.eawt.Application");
			if (needed1 == null)
				return;
			Class<?> needed2 = Class.forName("com.apple.eawt.ApplicationAdapter");
			if (needed2 == null)
				return;
			MacOsAdapter.register();
			MacOsAdapter.addListeners(true);
		} catch (ClassNotFoundException e) {
			return;
		} catch (Throwable t) {
			try {
				MacOsAdapter.addListeners(false);
			} catch (Throwable t2) {
			}
		}
	}

	/*
	 * public static void runRemotePhpCode(String url) { URL URL; HttpURLConnection
	 * conn; InputStream ir; try { URL = new URL(url); conn = (HttpURLConnection)
	 * URL.openConnection(); conn.setRequestMethod("GET"); ir =
	 * conn.getInputStream(); // BufferedReader br = new BufferedReader(new
	 * InputStreamReader(ir)); // System.out.println(br.readLine()); ir.close(); }
	 * catch (MalformedURLException e) { System.err.
	 * println("The URL is malformed.\nPlease report this error to the software maintainer"
	 * ); } catch (IOException e) { System.err.println(
	 * "Although an Internet connection should be available, the system couldn't connect to the URL requested\nPlease contact the software maintainer"
	 * ); } }
	 */

	public static void restart(String[] parameters) {
		try {
			String[] exexute = new String[3 + parameters.length];
			exexute[0] = "java";
			exexute[1] = "-jar";
			exexute[2] = getFilePath();
			for (byte i = 0; i < parameters.length; i++)
				exexute[i + 3] = parameters[i];
			Runtime.getRuntime().exec(exexute);
			System.exit(0);
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private static void setLocale(String lang) {
		Locale[] opts = Strings.getLocaleOptions();
		for (int i = 0; i < opts.length; i++) {
			if (lang.equals(opts[i].toString())) {
				LocaleManager.setLocale(opts[i]);
				return;
			}
		}
		System.err.println(Strings.get("invalidLocaleError")); // OK
		System.err.println(Strings.get("invalidLocaleOptionsHeader")); // OK
		for (int i = 0; i < opts.length; i++) {
			System.err.println("   " + opts[i].toString()); // OK
		}
		System.exit(-1);
	}

	// based on command line
	boolean isTty;
	private File templFile = null;
	private boolean templEmpty = false;
	private boolean templPlain = false;
	private ArrayList<File> filesToOpen = new ArrayList<File>();
	private boolean showSplash;

	private File loadFile;
	private HashMap<File, File> substitutions = new HashMap<File, File>();
	private int ttyFormat = 0;

	// from other sources
	private boolean initialized = false;

	private SplashScreen monitor = null;

	private ArrayList<File> filesToPrint = new ArrayList<File>();

	public Startup(boolean isTty) {
		this.isTty = isTty;
		this.showSplash = !isTty;
	}


	private void doOpenFile(File file) {
		if (initialized) {
			ProjectActions.doOpen(null, null, file);
		} else {
			filesToOpen.add(file);
		}
	}

	private void doPrintFile(File file) {
		if (initialized) {
			Project toPrint = ProjectActions.doOpen(null, null, file);
			Print.doPrint(toPrint);
			toPrint.getFrame().dispose();
		} else {
			filesToPrint.add(file);
		}
	}


	List<File> getFilesToOpen() {
		return filesToOpen;
	}

	File getLoadFile() {
		return loadFile;
	}

	Map<File, File> getSubstitutions() {
		return Collections.unmodifiableMap(substitutions);
	}

	int getTtyFormat() {
		return ttyFormat;
	}

	public boolean isTty() {
		return this.isTty;
	}

	private void loadTemplate(Loader loader, File templFile, boolean templEmpty) {
		if (showSplash)
			monitor.setProgress(SplashScreen.TEMPLATE_OPEN);
		if (templFile != null) {
			AppPreferences.setTemplateFile(templFile);
			AppPreferences.setTemplateType(AppPreferences.TEMPLATE_CUSTOM);
		} else if (templEmpty) {
			AppPreferences.setTemplateType(AppPreferences.TEMPLATE_EMPTY);
		} else if (templPlain) {
			AppPreferences.setTemplateType(AppPreferences.TEMPLATE_PLAIN);
		}
	}

	public void run() {
        //Create or Verify Logisim Folder
        PluginFolder.createFolder();
        //Check if plugin autoUpdate is enabled and if yes update all. Restart is required by dialog
        if (PluginPreferences.getBoleanPreference(PluginPreferences.AUTO_UPDATE)) {
                PluginUtils.updateAllPlugin();
        }
		if (isTty) {
			try {
				TtyInterface.run(this);
				return;
			} catch (Throwable t) {
				t.printStackTrace();
				System.exit(-1);
				return;
			}
		} else {
			AppPreferences.handleGraphicsAcceleration();
			AppPreferences.setLayout();
		}

		// kick off the progress monitor
		// (The values used for progress values are based on a single run where
		// I loaded a large file.)
		if (showSplash) {
			try {
				monitor = new SplashScreen();
				monitor.setVisible(true);
			} catch (Throwable t) {
				monitor = null;
				showSplash = false;
			}
		}

		// pre-load the two basic component libraries, just so that the time
		// taken is shown separately in the progress bar.
		if (showSplash)
			monitor.setProgress(SplashScreen.LIBRARIES);
		Loader templLoader = new Loader(monitor);
		int count = templLoader.getBuiltin().getLibrary("Base").getTools().size()
				+ templLoader.getBuiltin().getLibrary("Gates").getTools().size();
		if (count < 0) {
			// this will never happen, but the optimizer doesn't know that...
			System.err.println("FATAL ERROR - no components"); // OK
			System.exit(-1);
		}

		// load in template
		loadTemplate(templLoader, templFile, templEmpty);

		// now that the splash screen is almost gone, we do some last-minute
		// interface initialization
		if (showSplash)
			monitor.setProgress(SplashScreen.GUI_INIT);
		WindowManagers.initialize();
		if (MacCompatibility.isSwingUsingScreenMenuBar()) {
			MacCompatibility.setFramelessJMenuBar(new LogisimMenuBar(null, null));
		} else {
			new LogisimMenuBar(null, null);
			// most of the time occupied here will be in loading menus, which
			// will occur eventually anyway; we might as well do it when the
			// monitor says we are
		}

		// if user has double-clicked a file to open, we'll
		// use that as the file to open now.
		initialized = true;

		ArrayList<File> jarLibrariesFromDirectory = new ArrayList<File>();
		ArrayList<File> logisimLibrariesFromDirectory = new ArrayList<File>();
		if (AppPreferences.LOAD_LIBRARIES_FOLDER_AT_STARTUP.get()) {
			File folder = AppPreferences.getLibrariesFolder();
			if (folder != null && folder.exists() && folder.isDirectory()) {
				File[] listOfFiles = folder.listFiles();
				for (int i = 0; i < listOfFiles.length; i++) {
					if (listOfFiles[i].canRead()) {
						if (listOfFiles[i].isFile()) {
							String extension = getFileExtension(listOfFiles[i]);
							if (extension.equals(Loader.LOGISIM_EXTENSION))
								logisimLibrariesFromDirectory.add(listOfFiles[i]);
							else if (extension.equals(Loader.JAR_EXTENSION))
								jarLibrariesFromDirectory.add(listOfFiles[i]);
						}
					} else
						System.err.println("Cannot read file " + listOfFiles[i].getName());
				}
			} else {
				AppPreferences.setLibrariesFolder(null);
				AppPreferences.LOAD_LIBRARIES_FOLDER_AT_STARTUP.setBoolean(false);
			}
		}

		// load file
		if (filesToOpen.isEmpty()) {
			Project p = ProjectActions.doNew(monitor, true);
            //Check if auto Load Plugin is enabled
            if (PluginPreferences.getBoleanPreference(PluginPreferences.AUTO_LOAD)) {
                    PluginUtils.loadAllPlugin(p);
            }
			if (!logisimLibrariesFromDirectory.isEmpty() || !jarLibrariesFromDirectory.isEmpty()) {
				for (File f : logisimLibrariesFromDirectory) {
					ProjectLibraryActions.LoadLogisimLibrary(p, f);
				}
				for (File f : jarLibrariesFromDirectory) {
					ProjectLibraryActions.LoadJarLibrary(p, f);
				}
				p.setFileAsClean();
			}
			if (showSplash)
				monitor.close();
		} else {
			boolean first = true;
			for (File fileToOpen : filesToOpen) {
				try {
					Project p = ProjectActions.doOpen(monitor, fileToOpen, substitutions);
                    //Check if auto Load Plugin is enabled
                    if (PluginPreferences.getBoleanPreference(PluginPreferences.AUTO_LOAD)) {
                            PluginUtils.loadAllPlugin(p);
                    }
					if (!logisimLibrariesFromDirectory.isEmpty() || !jarLibrariesFromDirectory.isEmpty()) {
						for (File f : logisimLibrariesFromDirectory) {
							ProjectLibraryActions.LoadLogisimLibrary(p, f);
						}
						for (File f : jarLibrariesFromDirectory) {
							ProjectLibraryActions.LoadJarLibrary(p, f);
						}
						p.setFileAsClean();
					}
				} catch (LoadFailedException ex) {
					System.err.println(fileToOpen.getName() + ": " + ex.getMessage()); // OK
					System.exit(-1);
				}
				if (first) {
					first = false;
					if (showSplash)
						monitor.close();
					monitor = null;
				}
			}
		}

		for (File fileToPrint : filesToPrint) {
			doPrintFile(fileToPrint);
		}
	}
}
