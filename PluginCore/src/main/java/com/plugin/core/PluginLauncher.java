package com.plugin.core;


import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.app.Instrumentation;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.plugin.content.LoadedPlugin;
import com.plugin.content.PluginDescriptor;
import com.plugin.core.app.ActivityThread;
import com.plugin.core.app.AndroidAppApplication;
import com.plugin.core.localservice.LocalServiceManager;
import com.plugin.core.manager.PluginActivityMonitor;
import com.plugin.core.manager.PluginManagerHelper;
import com.plugin.core.systemservice.AndroidWebkitWebViewFactoryProvider;
import com.plugin.util.LogUtil;
import com.plugin.util.ProcessUtil;
import com.plugin.util.RefInvoker;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * <Pre>
 * @author cailiming
 * </Pre>
 *
 */
public class PluginLauncher implements Serializable {

	private static PluginLauncher runtime;

	private HashMap<String, LoadedPlugin> loadedPluginMap = new HashMap<String, LoadedPlugin>();

	private PluginLauncher() {
		if (!ProcessUtil.isPluginProcess()) {
			throw new IllegalAccessError("本类仅在插件进程使用");
		}
	}

	public static PluginLauncher instance() {
		if (runtime == null) {
			synchronized (PluginLauncher.class) {
				if (runtime == null) {
					runtime = new PluginLauncher();
				}
			}
		}
		return runtime;
	}

	public LoadedPlugin getRunningPlugin(String packageName) {
		return loadedPluginMap.get(packageName);
	}

	public LoadedPlugin startPlugin(String packageName) {
		PluginDescriptor pluginDescriptor = PluginManagerHelper.getPluginDescriptorByPluginId(packageName);
		if (pluginDescriptor != null) {
			return startPlugin(pluginDescriptor);
		}
		return null;
	}

	public LoadedPlugin startPlugin(PluginDescriptor pluginDescriptor) {
		LoadedPlugin plugin = loadedPluginMap.get(pluginDescriptor.getPackageName());
		if (plugin == null) {

			long startAt = System.currentTimeMillis();
			LogUtil.i("正在初始化插件 " + pluginDescriptor.getPackageName() + ": Resources, DexClassLoader, Context, Application");
			LogUtil.v("插件信息", pluginDescriptor.getVersion(), pluginDescriptor.getInstalledPath());

			Resources pluginRes = PluginCreator.createPluginResource(
					PluginLoader.getApplication().getApplicationInfo().sourceDir,
					PluginLoader.getApplication().getResources(), pluginDescriptor);

			if (pluginRes == null) {
				LogUtil.e("初始化插件失败 : res");
			}

			long t1 = System.currentTimeMillis();
			LogUtil.i("初始化插件资源耗时:" + (t1 - startAt));

			DexClassLoader pluginClassLoader = PluginCreator.createPluginClassLoader(
							pluginDescriptor.getInstalledPath(),
							pluginDescriptor.isStandalone(),
							pluginDescriptor.getDependencies(),
							pluginDescriptor.getMuliDexList());

			long t12 = System.currentTimeMillis();
			LogUtil.w("初始化插件DexClassLoader耗时:" + (t12 - t1));

			Context pluginContext = PluginCreator.createPluginContext(
					pluginDescriptor,
					PluginLoader.getApplication().getBaseContext(),
					pluginRes,
					pluginClassLoader);

			//插件Context默认主题设置为插件application主题
			pluginContext.setTheme(pluginDescriptor.getApplicationTheme());

			long t13 = System.currentTimeMillis();
			LogUtil.i("初始化插件Theme耗时:" + (t13 - t12));

			plugin = new LoadedPlugin(pluginDescriptor.getPackageName(),
					pluginDescriptor.getInstalledPath(),
					pluginContext,
					pluginClassLoader);

			loadedPluginMap.put(pluginDescriptor.getPackageName(), plugin);

			Application pluginApplication = callPluginApplicationOnCreate(pluginContext, pluginClassLoader, pluginDescriptor);

			plugin.pluginApplication = pluginApplication;//这里之所以不放在LoadedPlugin的构造器里面，是因为contentprovider在安装时loadclass，造成死循环

			long t3 = System.currentTimeMillis();
			LogUtil.w("初始化插件Application耗时:" + (t3 - t13));

			try {
				ActivityThread.installPackageInfo(PluginLoader.getApplication(), pluginDescriptor.getPackageName(), pluginDescriptor,
						pluginClassLoader, pluginRes, pluginApplication);
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}

			long endAt = System.currentTimeMillis();
			LogUtil.i("初始化插件" + pluginDescriptor.getPackageName() + "完成, 总耗时:" + (endAt - startAt));

		} else {
			//LogUtil.d("IS RUNNING", packageName);
		}

		return plugin;
	}

	private Application callPluginApplicationOnCreate(Context pluginContext, DexClassLoader classLoader, PluginDescriptor pluginDescriptor) {

		Application application = null;

		try {
			LogUtil.d("创建插件Application", pluginDescriptor.getApplicationName());

			//为了支持插件中使用multidex
			((PluginContextTheme)pluginContext).setCrackPackageManager(true);

			application = Instrumentation.newApplication(classLoader.loadClass(pluginDescriptor.getApplicationName()),
					pluginContext);

			//为了支持插件中使用multidex
			((PluginContextTheme)pluginContext).setCrackPackageManager(false);

		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} 	catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}

		//安装ContentProvider, 在插件Application对象构造以后，oncreate调用之前
		PluginInjector.installContentProviders(PluginLoader.getApplication(), application, pluginDescriptor.getProviderInfos().values());

		//执行onCreate
		if (application != null) {

			((PluginContextTheme)pluginContext).setPluginApplication(application);

			LogUtil.v("屏蔽插件中的UncaughtExceptionHandler");

			//先拿到宿主的crashHandler
			Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();

			application.onCreate();

			// 再还原宿主的crashHandler，这里之所以需要还原CrashHandler，
			// 是因为如果插件中自己设置了自己的crashHandler（通常是在oncreate中），
			// 会导致当前进程的主线程的handler被意外修改。
			// 如果有多个插件都有设置自己的crashHandler，也会导致混乱
			// 所以这里直接屏蔽掉插件的crashHandler
			//TODO 或许也可以做成消息链进行分发？
			Thread.setDefaultUncaughtExceptionHandler(old);

			if (Build.VERSION.SDK_INT >= 14) {
				// ActivityLifecycleCallbacks 的回调实际是由Activity内部在自己的声明周期函数内主动调用application的注册的callback触发的
				//由于我们把插件Activity内部的application成员变量替换调用了  会导致不会触发宿主中注册的ActivityLifecycleCallbacks
				//那么我们在这里给插件的Application对象注册一个callback bridge。将插件的call发给宿主的call，
				//从而使得宿主application中注册的callback能监听到插件Activity的声明周期
				application.registerActivityLifecycleCallbacks(new LifecycleCallbackBridge());
			} else {
				//对于小于14的版本，影响是，StubActivity的绑定关系不能被回收，
				// 意味着宿主配置的非Stand的StubActivity的个数不能小于插件中对应的类型的个数的总数，否则可能会出现找不到映射的StubActivity
			}

		}

		return application;
	}

	public void stopPlugin(String packageName, PluginDescriptor pluginDescriptor) {

		LoadedPlugin plugin = getRunningPlugin(packageName);

		if (plugin == null) {
			LogUtil.w("插件未运行", packageName);
			return;
		}
		//
		//退出WebView, LocalService、Activity、BroadcastReceiver、LocalBroadcastManager, Service、AssetManager、ContentProvider、fragment
		//

		//退出webview
		LogUtil.d("退出webview");
		new Handler(Looper.getMainLooper()).post(new Runnable() {
			@Override
			public void run() {
				AndroidWebkitWebViewFactoryProvider.switchWebViewContext(PluginLoader.getApplication());
			}
		});

		//退出LocalService
		LogUtil.d("退出LocalService");
		LocalServiceManager.unRegistService(pluginDescriptor);
		//TODO 还要通知宿主进程退出localService，不过不通知其实本身也不会坏影响。

		//退出Activity
		LogUtil.d("退出Activity");
		PluginLoader.getApplication().sendBroadcast(new Intent(plugin.pluginPackageName + PluginActivityMonitor.ACTION_UN_INSTALL_PLUGIN));

		//退出 LocalBroadcastManager
		LogUtil.d("退出LocalBroadcastManager");
		Object mInstance = RefInvoker.getStaticFieldObject("android.support.v4.content.LocalBroadcastManager", "mInstance");
		if (mInstance != null) {
			HashMap<BroadcastReceiver, ArrayList<IntentFilter>> mReceivers = (HashMap<BroadcastReceiver, ArrayList<IntentFilter>>)RefInvoker.getFieldObject(mInstance,
					"android.support.v4.content.LocalBroadcastManager", "mReceivers");
			if (mReceivers != null) {
				Iterator<BroadcastReceiver> ir = mReceivers.keySet().iterator();
				while(ir.hasNext()) {
					BroadcastReceiver item = ir.next();
					if (item.getClass().getClassLoader() == plugin.pluginClassLoader) {
						RefInvoker.invokeMethod(mInstance, "android.support.v4.content.LocalBroadcastManager",
								"unregisterReceiver", new Class[]{BroadcastReceiver.class}, new Object[]{item});
					}
				}
			}
		}

		//退出Service
		//bindservie启动的service应该不需要处理，退出activity的时候会unbind
		Map<IBinder, Service> map = ActivityThread.getAllServices();
		if (map != null) {
			Collection<Service> list = map.values();
			for (Service s :list) {
				if (s.getClass().getClassLoader() == plugin.pluginClassLoader) {
					s.stopSelf();
				}
			}
		}

		//退出BroadcastReceiver
		//广播一般有个注册方式
		//1、activity、service注册
		//		这种方式，在上一步Activitiy、service退出时会自然退出，所以不用处理
		//2、application注册
		//      这里需要处理这种方式注册的广播，这种方式注册的广播会被PluginContextTheme对象记录下来
		LogUtil.d("退出BroadcastReceiver");
		((PluginContextTheme) plugin.pluginApplication.getBaseContext()).unregisterAllReceiver();

		//退出AssetManager
		//pluginDescriptor.getPluginContext().getResources().getAssets().close();

		//退出ContentProvider
		//TODO ContentProvider如何退出？
		//ActivityThread.releaseProvider(IContentProvider provider, boolean stable)

		//退出fragment
		//即退出由FragmentManager保存的Fragment
		//TODO fragment如何退出？

		loadedPluginMap.remove(packageName);
	}

	public boolean isRunning(String packageName) {
		return loadedPluginMap.get(packageName) != null;
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
	static class LifecycleCallbackBridge implements ActivityLifecycleCallbacks {
		@Override
		public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
			AndroidAppApplication.dispatchActivityCreated(PluginLoader.getApplication(), activity, savedInstanceState);
		}

		@Override
		public void onActivityStarted(Activity activity) {
			AndroidAppApplication.dispatchActivityStarted(PluginLoader.getApplication(), activity);
		}

		@Override
		public void onActivityResumed(Activity activity) {
			AndroidAppApplication.dispatchActivityResumed(PluginLoader.getApplication(), activity);
		}

		@Override
		public void onActivityPaused(Activity activity) {
			AndroidAppApplication.dispatchActivityPaused(PluginLoader.getApplication(), activity);
		}

		@Override
		public void onActivityStopped(Activity activity) {
			AndroidAppApplication.dispatchActivityStopped(PluginLoader.getApplication(), activity);
		}

		@Override
		public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
			AndroidAppApplication.dispatchActivitySaveInstanceState(PluginLoader.getApplication(), activity, outState);
		}

		@Override
		public void onActivityDestroyed(Activity activity) {
			AndroidAppApplication.dispatchActivityDestroyed(PluginLoader.getApplication(), activity);
		}
	}
}
