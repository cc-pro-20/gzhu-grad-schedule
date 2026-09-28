package android.content;

import android.content.res.AssetManager;

import java.io.File;

/** 测试桩：Context。只实现本项目用到的几个方法。 */
public class Context {

    private final File filesDir;
    private final AssetManager assets;

    public Context(File filesDir, File assetsDir) {
        this.filesDir = filesDir;
        this.assets = new AssetManager(assetsDir);
    }

    public Context getApplicationContext() {
        return this;
    }

    public File getFilesDir() {
        return filesDir;
    }

    public AssetManager getAssets() {
        return assets;
    }
}
