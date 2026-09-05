package manualcontrol.install;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;

public final class CrazyPatchInstaller {

    private static final String PAYLOAD_AGENT = "/installer_payload/manual-control-patch.jar";
    private static final String PAYLOAD_PACK_PREFIX = "/installer_payload/packs/";
    private static final String EXTERNAL_PAYLOAD_DIR = "installer_payload";
    private static final String PATCH_DIR_NAME = "bcu-crazy-patch";
    private static final String AGENT_NAME = "manual-control-patch.jar";
    private static final String PATCH_PACKS_DIR = "packs";
    private static final String CRAZY_PACK_NAME = "BCU Crazy - bcucrazy.pack.bcuzip";
    private static final String ROOT_LAUNCHER = "Crazy BCU.bat";
    private static final String PATCH_LAUNCHER = "Crazy BCU.bat";
    private static final String ROOT_HIDDEN_LAUNCHER = "Crazy BCU (No Console).vbs";
    private static final String PATCH_HIDDEN_LAUNCHER = "Crazy BCU (No Console).vbs";
    private static final String UNINSTALLER = "Uninstall Crazy BCU.bat";
    private static final String VERSION_FILE = "INSTALL_VERSION.txt";
    private static final String LEGACY_ADVENTURE_SAVES = "adventure_saves";
    private static final String ADVENTURE_SAVES = "adventure_saves";

    public static void main(String[] args) {
        try {
            boolean uninstall = false;
            String path = null;
            for (String a : args) {
                if ("--uninstall".equals(a) || "-u".equals(a)) uninstall = true;
                else if (!a.startsWith("-")) path = a;
            }

            File bcuDir = path == null ? installerDir() : new File(path);
            if (bcuDir.isFile()) bcuDir = bcuDir.getParentFile();

            if (path == null && pickBcuJar(bcuDir) == null && bcuDir != null
                    && pickBcuJar(bcuDir.getParentFile()) != null)
                bcuDir = bcuDir.getParentFile();
            if (bcuDir == null || !bcuDir.isDirectory())
                fail("Could not find the BCU folder. Put this installer inside the BCU folder and run it again.");

            if (uninstall) {
                uninstall(bcuDir);
                success("Crazy BCU launcher removed from:\n" + bcuDir.getAbsolutePath()
                        + "\n\nThe bcu-crazy-patch folder was left in place so saves are not deleted.");
                return;
            }

            File bcuJar = pickBcuJar(bcuDir);
            if (bcuJar == null)
                fail("No BCU jar was found in:\n" + bcuDir.getAbsolutePath()
                        + "\n\nPut this installer next to your BCU jar, then run it again.");

            install(bcuDir);
            success("Crazy Patch + Adventure Mode installed.\n\n"
                    + "Folder:\n" + new File(bcuDir, PATCH_DIR_NAME).getAbsolutePath()
                    + "\n\nLaunch with:\n" + new File(bcuDir, ROOT_HIDDEN_LAUNCHER).getName()
                    + "\n\nFor a visible diagnostic console, use:\n" + new File(bcuDir, ROOT_LAUNCHER).getName()
                    + "\n\nThis launcher does not modify the BCU jar. If BCU is updated later, keep using the same launcher; it will pick the newest BCU jar in this folder.");
        } catch (InstallError e) {
            fail(e.getMessage());
        } catch (Throwable t) {
            fail("Unexpected error: " + t);
        }
    }

    private static void install(File bcuDir) throws Exception {
        File patchDir = new File(bcuDir, PATCH_DIR_NAME);
        if (!patchDir.exists() && !patchDir.mkdirs())
            throw new InstallError("Could not create folder:\n" + patchDir.getAbsolutePath());

        writeRequiredPayload(PAYLOAD_AGENT, AGENT_NAME,
                new File(patchDir, AGENT_NAME), "manual-control-patch.jar");
        installBundledPacks(bcuDir, patchDir);
        writeText(new File(patchDir, PATCH_LAUNCHER), patchLauncher());
        writeText(new File(patchDir, PATCH_HIDDEN_LAUNCHER),
                hiddenLauncher("Crazy BCU.bat", "manual-control.log"));
        writeText(new File(bcuDir, ROOT_LAUNCHER), rootLauncher());
        writeText(new File(bcuDir, ROOT_HIDDEN_LAUNCHER),
                hiddenLauncher("bcu-crazy-patch\\Crazy BCU.bat",
                        "bcu-crazy-patch\\manual-control.log"));
        writeText(new File(bcuDir, UNINSTALLER), uninstaller());
        writeText(new File(patchDir, "README - Crazy BCU.txt"), readme());
        writeText(new File(patchDir, VERSION_FILE), "Crazy BCU install\n"
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n");
        copyLegacyAdventureSaves(bcuDir, patchDir);
    }

    private static void uninstall(File bcuDir) throws Exception {
        deleteFile(new File(bcuDir, ROOT_LAUNCHER));
        deleteFile(new File(bcuDir, ROOT_HIDDEN_LAUNCHER));
        deleteFile(new File(bcuDir, UNINSTALLER));
    }

    private static void writeRequiredPayload(String resource, String externalRelative,
                                             File target, String label) throws Exception {
        if (!writeExternalPayload(externalRelative, target)
                && !writeBundledResource(resource, target))
            throw new InstallError("Installer payload is missing " + label
                    + ". Keep installer_payload beside the installer or rebuild it.");
    }

    private static boolean writeExternalPayload(String relative, File target) throws Exception {
        File source = new File(new File(installerDir(), EXTERNAL_PAYLOAD_DIR), relative);
        if (!source.isFile()) return false;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new InstallError("Could not create folder:\n" + parent.getAbsolutePath());
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static boolean writeBundledResource(String resource, File target) throws Exception {
        InputStream in = CrazyPatchInstaller.class.getResourceAsStream(resource);
        if (in == null)
            return false;
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new InstallError("Could not create folder:\n" + parent.getAbsolutePath());
        File tmp = File.createTempFile("manual-control-patch", ".jar", target.getParentFile());
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                copy(in, out);
            } finally {
                out.close();
                in.close();
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        return true;
    }

    private static void installBundledPacks(File bcuDir, File patchDir) throws Exception {
        File patchPack = new File(new File(patchDir, PATCH_PACKS_DIR), CRAZY_PACK_NAME);
        if (!writeExternalPayload("packs/" + CRAZY_PACK_NAME, patchPack)
                && !writeBundledResource(PAYLOAD_PACK_PREFIX + CRAZY_PACK_NAME, patchPack))
            return;

        File bcuPacks = new File(bcuDir, "packs");
        if (!bcuPacks.exists() && !bcuPacks.mkdirs())
            throw new InstallError("Could not create folder:\n" + bcuPacks.getAbsolutePath());
        Files.copy(patchPack.toPath(), new File(bcuPacks, CRAZY_PACK_NAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void copyLegacyAdventureSaves(File bcuDir, File patchDir) {
        File oldRoot = new File(bcuDir, LEGACY_ADVENTURE_SAVES);
        File newRoot = new File(patchDir, ADVENTURE_SAVES);
        if (!oldRoot.isDirectory() || newRoot.exists()) return;
        try {
            copyDir(oldRoot, newRoot);
        } catch (Throwable ignored) {}
    }

    private static void copyDir(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs())
                throw new java.io.IOException("Could not create " + dst);
            File[] kids = src.listFiles();
            if (kids == null) return;
            for (File k : kids)
                copyDir(k, new File(dst, k.getName()));
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs())
                throw new java.io.IOException("Could not create " + parent);
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static File pickBcuJar(File dir) {
        if (dir == null) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        File best = null;
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (!n.startsWith("bcu") || !n.endsWith(".jar")) continue;
            if (n.contains("manual-control") || n.contains("installer") || n.contains("directedit")
                    || n.contains("backup") || n.contains("patch")) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private static File installerDir() {
        try {
            URI uri = CrazyPatchInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File self = new File(uri);
            return self.isFile() ? self.getParentFile() : self;
        } catch (Throwable t) {
            return new File(".");
        }
    }

    private static void writeText(File file, String text) throws Exception {
        byte[] data = text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), data);
    }

    private static void deleteFile(File f) throws Exception {
        if (f.isFile() && !f.delete())
            throw new InstallError("Could not delete:\n" + f.getAbsolutePath());
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
    }

    private static String rootLauncher() {
        return "@echo off\n"
                + "setlocal\n"
                + "set \"ROOT=%~dp0\"\n"
                + "if \"%ROOT:~-1%\"==\"\\\" set \"ROOT=%ROOT:~0,-1%\"\n"
                + "call \"%ROOT%\\bcu-crazy-patch\\Crazy BCU.bat\" %*\n";
    }

    private static String uninstaller() {
        return "@echo off\n"
                + "setlocal\n"
                + "set \"ROOT=%~dp0\"\n"
                + "if \"%ROOT:~-1%\"==\"\\\" set \"ROOT=%ROOT:~0,-1%\"\n"
                + "del \"%ROOT%\\Crazy BCU.bat\" >nul 2>nul\n"
                + "del \"%ROOT%\\Crazy BCU (No Console).vbs\" >nul 2>nul\n"
                + "del \"%ROOT%\\Uninstall Crazy BCU.bat\" >nul 2>nul\n"
                + "echo Crazy BCU launcher removed.\n"
                + "echo The bcu-crazy-patch folder was kept so Adventure saves are not deleted.\n"
                + "pause\n";
    }

    private static String patchLauncher() {
        return "@echo off\n"
                + "REM Crazy BCU launcher generated by the installer.\n"
                + "setlocal enabledelayedexpansion\n"
                + "set \"SCRIPT_DIR=%~dp0\"\n"
                + "if \"%SCRIPT_DIR:~-1%\"==\"\\\" set \"SCRIPT_DIR=%SCRIPT_DIR:~0,-1%\"\n"
                + "for %%i in (\"%SCRIPT_DIR%\") do set \"SCRIPT_FOLDER=%%~nxi\"\n"
                + "if /I \"!SCRIPT_FOLDER!\"==\"bcu-crazy-patch\" (\n"
                + "    set \"PATCH_DIR=!SCRIPT_DIR!\"\n"
                + "    for %%i in (\"!PATCH_DIR!\\..\") do set \"BCU_DIR=%%~fi\"\n"
                + ") else (\n"
                + "    set \"BCU_DIR=!SCRIPT_DIR!\"\n"
                + "    set \"PATCH_DIR=!BCU_DIR!\\bcu-crazy-patch\"\n"
                + ")\n"
                + "set \"LOG_FILE=!PATCH_DIR!\\manual-control.log\"\n"
                + "if exist \"!PATCH_DIR!\\packs\\*.bcuzip\" (\n"
                + "    if not exist \"!BCU_DIR!\\packs\" mkdir \"!BCU_DIR!\\packs\"\n"
                + "    copy /y \"!PATCH_DIR!\\packs\\*.bcuzip\" \"!BCU_DIR!\\packs\\\" >nul\n"
                + ")\n"
                + "for /f \"usebackq delims=\" %%f in (`powershell -NoProfile -ExecutionPolicy Bypass -Command \"$d='!PATCH_DIR!'; if (Test-Path -LiteralPath $d) { Get-ChildItem -LiteralPath $d -Filter 'manual-control-patch*.jar' | Where-Object { $_.Name -notmatch 'installer|backup' } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1 -ExpandProperty FullName }\"`) do set \"AGENT=%%f\"\n"
                + "for /f \"usebackq delims=\" %%f in (`powershell -NoProfile -ExecutionPolicy Bypass -Command \"$d='!BCU_DIR!'; if (Test-Path -LiteralPath $d) { Get-ChildItem -LiteralPath $d -Filter 'BCU*.jar' | Where-Object { $_.Name -notmatch 'manual-control|installer|directedit|backup|patch' } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1 -ExpandProperty FullName }\"`) do set \"BCU_JAR=%%f\"\n"
                + "if not defined AGENT (\n"
                + "    echo [ERROR] Patch file not found in: !PATCH_DIR!\n"
                + "    echo Re-run the Crazy BCU installer.\n"
                + "    if not defined CRAZY_BCU_HIDDEN pause\n"
                + "    exit /b 1\n"
                + ")\n"
                + "if not defined BCU_JAR (\n"
                + "    echo [ERROR] No BCU jar found in: !BCU_DIR!\n"
                + "    echo Put this launcher next to your BCU jar.\n"
                + "    if not defined CRAZY_BCU_HIDDEN pause\n"
                + "    exit /b 1\n"
                + ")\n"
                + "echo.\n"
                + "echo =========================================================\n"
                + "echo   Crazy BCU\n"
                + "echo =========================================================\n"
                + "echo Agent: !AGENT!\n"
                + "echo BCU  : !BCU_JAR!\n"
                + "echo Data : !PATCH_DIR!\n"
                + "echo Log  : !LOG_FILE!\n"
                + "echo.\n"
                + "cd /d \"!BCU_DIR!\"\n"
                + "java \"-Dmanualcontrol.home=!PATCH_DIR!\" \"-Dmanualcontrol.log=!LOG_FILE!\" \"-javaagent:!AGENT!\" -jar \"!BCU_JAR!\"\n"
                + "set \"EXIT_CODE=!errorlevel!\"\n"
                + "if not \"!EXIT_CODE!\"==\"0\" (\n"
                + "    echo.\n"
                + "    echo [ERROR] BCU exited with error code !EXIT_CODE!\n"
                + "    if not defined CRAZY_BCU_HIDDEN pause\n"
                + ")\n"
                + "endlocal & exit /b %EXIT_CODE%\n";
    }

    private static String hiddenLauncher(String launcherRelative, String logRelative) {
        return "Option Explicit\n"
                + "\n"
                + "Dim shell, environment, fso, scriptDir, launcher, command, exitCode, logFile\n"
                + "\n"
                + "Set shell = CreateObject(\"WScript.Shell\")\n"
                + "Set environment = shell.Environment(\"Process\")\n"
                + "Set fso = CreateObject(\"Scripting.FileSystemObject\")\n"
                + "\n"
                + "scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)\n"
                + "launcher = fso.BuildPath(scriptDir, \"" + launcherRelative + "\")\n"
                + "logFile = fso.BuildPath(scriptDir, \"" + logRelative + "\")\n"
                + "\n"
                + "If Not fso.FileExists(launcher) Then\n"
                + "    MsgBox \"Cannot find the Crazy BCU launcher:\" & vbCrLf & launcher, vbCritical, \"Crazy BCU\"\n"
                + "    WScript.Quit 1\n"
                + "End If\n"
                + "\n"
                + "environment(\"CRAZY_BCU_HIDDEN\") = \"1\"\n"
                + "command = \"cmd.exe /d /s /c \" & Chr(34) & Chr(34) & launcher & Chr(34) & Chr(34)\n"
                + "exitCode = shell.Run(command, 0, True)\n"
                + "\n"
                + "If exitCode <> 0 Then\n"
                + "    MsgBox \"Crazy BCU could not start or closed with error code \" & exitCode & \".\" _\n"
                + "        & vbCrLf & vbCrLf & \"Details: \" & logFile, vbExclamation, \"Crazy BCU\"\n"
                + "End If\n"
                + "\n"
                + "WScript.Quit exitCode\n";
    }

    private static String readme() {
        return "Crazy BCU + Adventure Mode\n"
                + "\n"
                + "How to use:\n"
                + "1. Keep this folder next to your BCU jar.\n"
                + "2. Run Crazy BCU (No Console).vbs from the BCU folder.\n"
                + "   Use Crazy BCU.bat only when you need the visible diagnostic console.\n"
                + "3. Adventure saves and patch logs stay inside this folder.\n"
                + "4. Bundled Crazy packs are stored here and mirrored into BCU's packs folder when needed.\n"
                + "\n"
                + "BCU updates:\n"
                + "The launcher scans the BCU folder every time and picks the newest BCU*.jar.\n"
                + "You normally do not need to reinstall after replacing the BCU jar.\n";
    }

    private static void success(String msg) {
        System.out.println("[Crazy BCU Installer] OK\n" + msg);
        if (!GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("crazyinstaller.nogui"))
            JOptionPane.showMessageDialog(null, msg, "Crazy BCU - Installed", JOptionPane.INFORMATION_MESSAGE);
        System.exit(0);
    }

    private static void fail(String msg) {
        System.err.println("[Crazy BCU Installer] FAILED\n" + msg);
        if (!GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("crazyinstaller.nogui"))
            JOptionPane.showMessageDialog(null, msg, "Crazy BCU - Not Installed", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    private static final class InstallError extends Exception {
        InstallError(String msg) { super(msg); }
    }

    private CrazyPatchInstaller() {}
}
