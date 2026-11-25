package shop.terramoor.github.io.systemui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.error.XposedFrameworkError
import shop.terramoor.github.io.diagnostics.DiagnosticLog
import shop.terramoor.github.io.diagnostics.DiagnosticProvider
import shop.terramoor.github.io.diagnostics.diagnosticStack
import shop.terramoor.github.io.settings.CapsuleSettings
import java.lang.reflect.Method

class TerraMoorModule : XposedModule() {
    private var targetProcess = false
    private var diagnostics: DiagnosticLog? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        targetProcess = param.processName == NativeContract.SYSTEM_UI
        if (targetProcess) Log.i(TAG, "onModuleLoaded: SystemUI pid=${android.os.Process.myPid()}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!targetProcess) return
        if (!param.isFirstPackage || param.packageName != NativeContract.SYSTEM_UI) {
            Log.i(TAG, "onPackageReady filtered: first=${param.isFirstPackage}, systemUi=${param.packageName == NativeContract.SYSTEM_UI}")
            return
        }
        val hooks = ArrayList<HookHandle>()
        try {
            val journal = DiagnosticProvider.systemUiLog { moduleApplicationInfo.packageName }
            diagnostics = journal
            journal.note("onModuleLoaded reached, SystemUI pid=${android.os.Process.myPid()}; onPackageReady first=true; Xposed API $apiVersion")
            Log.i(TAG, "TerraMoor module entered SystemUI, pid=${android.os.Process.myPid()}")
            val host = NativeContract.Host(param.classLoader, ::optionalMissing)
            journal.note("Required host contract resolved: classes=${NativeContract.HOST},${host.mediaClassName}; cos16=${host.isSeedlingMediaMode}")
            val runtime = CapsuleRuntime(host, journal, moduleApplicationInfo.packageName,
                { getRemotePreferences(CapsuleSettings.GROUP) }, ::installPlugin)
            hooks += after(host.attach) { instance, _, _ -> runtime.hostChanged(instance) }
            journal.note("Required hook installed: ${host.attach.declaringClass.name}#${host.attach.name}")
            hooks += after(host.detach) { instance, _, _ -> runtime.hostDetached(instance) }
            journal.note("Required hook installed: ${host.detach.declaringClass.name}#${host.detach.name}")
            host.pluginAdded?.let { method -> installOptional(hooks, "Plugin-added hook") {
                after(method) { instance, _, _ -> runtime.hostChanged(instance) }
            } }
            host.pluginRemoved?.let { method -> installOptional(hooks, "Plugin-removed hook") {
                after(method) { instance, _, _ -> runtime.pluginRemoved(instance) }
            } }
            val publication = installMediaPublication(host, runtime, hooks)
            host.pauseTimeout?.let { timeout -> installOptional(hooks, "Media pause timeout hook") {
                hook(timeout.run).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val retained = try {
                        chain.thisObject?.let(runtime::retainPausedCapsule) == true
                    } catch (failure: Throwable) {
                        if (!isExpectedFailure(failure)) throw failure
                        report("Media pause timeout", failure)
                        false
                    }
                    if (retained) null else chain.proceed()
                }
            } }
            // Prevent ColorOS 16 status-bar media capsule auto-collapse after timeout
            installOptional(hooks, "ColorOS 16 statusBarTimeoutRunnable hook") {
                val timeoutCls = param.classLoader.loadClass(
                    "com.oplus.systemui.seedlingservice.mediaControl.MediaControlTip\$statusBarTimeoutRunnable\$1",
                )
                val runMethod = timeoutCls.getDeclaredMethod("run")
                hook(runMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (runtime.shouldPreventCapsuleCollapse()) null else chain.proceed()
                }
            }
            installOptional(hooks, "ColorOS 16 MediaControlTip hide guard hook") {
                val tipCls = param.classLoader.loadClass(
                    "com.oplus.systemui.seedlingservice.mediaControl.MediaControlTip",
                )
                val cardCls = param.classLoader.loadClass(
                    "com.oplus.pantanal.seedling.bean.SeedlingCard",
                )
                val hideMethod = tipCls.getDeclaredMethod(
                    "hideStatusBarSeedlingCard",
                    cardCls,
                    Boolean::class.javaPrimitiveType,
                )
                val showFlagField = runCatching {
                    tipCls.getDeclaredField("isMediaSeedlingShowOnStatusBar").apply { isAccessible = true }
                }.getOrNull()
                val disableFlagsField = runCatching {
                    tipCls.getDeclaredField("disableSeedlingFlags").apply { isAccessible = true }
                }.getOrNull()
                runCatching {
                    val dataCls = param.classLoader.loadClass(
                        "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData",
                    )
                    val shouldHideMethod = tipCls.getDeclaredMethod(
                        "shouldStatusBarSeedlingCardHidden",
                        dataCls,
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                    )
                    hooks += hook(shouldHideMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                        if (runtime.shouldPreventCapsuleCollapse()) {
                            val enabled = showFlagField?.getBoolean(null) != false
                            val disabledByFlag = ((disableFlagsField?.getInt(null) ?: 0) and 1) != 0
                            if (enabled && !disabledByFlag) return@intercept false
                        }
                        chain.proceed()
                    }
                }
                hook(hideMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val isForceHide = chain.args.getOrNull(1) as? Boolean ?: false
                    if (!isForceHide && runtime.shouldPreventCapsuleCollapse()) null else chain.proceed()
                }
            }
            host.release?.let { method -> installOptional(hooks, "Media release hook") {
                after(method) { instance, _, _ -> runtime.mediaReleased(instance) }
            } }
            installOptional(hooks, "Lockscreen & AOD lyrics bridge hook") {
                val reentrant = ThreadLocal<Boolean>()
                val getStringMethod = android.media.MediaMetadata::class.java.getDeclaredMethod("getString", String::class.java)
                hook(getStringMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val res = chain.proceed()
                    if (res != null && (res as? String)?.isNotBlank() == true) return@intercept res
                    if (chain.args.firstOrNull() != shop.terramoor.github.io.lyrics.LyricPayload.KEY) return@intercept res
                    if (reentrant.get() == true) return@intercept res
                    val metadata = chain.thisObject as? android.media.MediaMetadata ?: return@intercept res
                    reentrant.set(true)
                    try {
                        runtime.bridgeLyricInfo(metadata) ?: res
                    } finally {
                        reentrant.set(false)
                    }
                }
            }
            runtime.fluidCloudHooked = true
            // Lockscreen & Notification media lyrics pipeline hook (ColorOS 16)
            installOptional(hooks, "Lockscreen loadLyricInBg hook") {
                val mgrCls = param.classLoader.loadClass("com.android.systemui.media.controls.pipeline.OplusMediaDataManagerEx")
                val lyricDataCls = param.classLoader.loadClass("com.android.systemui.media.controls.models.player.OplusMediaLyricData")
                val loadMethod = mgrCls.getDeclaredMethod(
                    "loadLyricInBg",
                    String::class.java,
                    android.media.MediaMetadata::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    lyricDataCls,
                )
                hook(loadMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val hasExistingLines = if (original != null) {
                        val lines = runCatching { lyricDataCls.getMethod("getLines").invoke(original) as? List<*> }.getOrNull()
                        !lines.isNullOrEmpty()
                    } else false
                    if (hasExistingLines) return@intercept original
                    val metadata = chain.args.getOrNull(1) as? android.media.MediaMetadata
                    val song = chain.args.getOrNull(2) as? String
                    val artist = chain.args.getOrNull(3) as? String
                    runtime.loadLockscreenLyricSync(param.classLoader, metadata, song, artist) ?: original
                }.also { runtime.lockscreenHooked = true }
            }
            // AOD & Lockscreen display policy unlock hooks (ColorOS 16)
            installOptional(hooks, "AOD OplusMediaLyricData.isEnabled hook") {
                val lyricDataCls = param.classLoader.loadClass("com.android.systemui.media.controls.models.player.OplusMediaLyricData")
                val isEnabledMethod = lyricDataCls.getDeclaredMethod("isEnabled", Int::class.javaPrimitiveType)
                hook(isEnabledMethod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (runtime.isLockscreenBridgeEnabled()) true else chain.proceed()
                }.also { runtime.aodHooked = true }
            }
            installOptional(hooks, "AOD Seedling LyricData.getEnabledAod hook") {
                val seedlingLyricCls = param.classLoader.loadClass("com.oplus.systemui.seedlingservice.mediaControl.LyricData")
                val getAod = seedlingLyricCls.getDeclaredMethod("getEnabledAod")
                hook(getAod).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (runtime.isLockscreenBridgeEnabled()) true else chain.proceed()
                }.also { runtime.aodHooked = true }
            }
            // Unlock ColorOS 16 AOD media whitelist (Spotify-only restriction -> allow ALL music players)
            installOptional(hooks, "AOD all-player whitelist unlock") {
                val aodCls = param.classLoader.loadClass("com.oplusos.systemui.aod.mediapanel.AodMediaDataListener")
                val allowAllList = object : java.util.AbstractList<String>() {
                    override val size: Int get() = 1
                    override fun get(index: Int): String = "com.spotify.music"
                    override fun contains(element: String?): Boolean = true
                }
                for (fieldName in listOf("aodMediaWhiteList", "aodMediaWhiteListExp")) {
                    runCatching {
                        val f = aodCls.getDeclaredField(fieldName).apply { isAccessible = true }
                        f.set(null, allowAllList)
                    }
                }
                val getter = aodCls.getDeclaredMethod("access\$getAodMediaWhiteListExp\$cp")
                hook(getter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (runtime.isAodWhitelistUnlocked()) allowAllList else chain.proceed()
                }.also {
                    runCatching {
                        val getterCn = aodCls.getDeclaredMethod("access\$getAodMediaWhiteList\$cp")
                        hooks += hook(getterCn).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                            if (runtime.isAodWhitelistUnlocked()) allowAllList else chain.proceed()
                        }
                    }
                    runtime.aodHooked = true
                }
            }
            installOptional(hooks, "AOD AodMediaViewHolder live lyrics hook") {
                val vhCls = param.classLoader.loadClass("com.oplusos.systemui.aod.mediapanel.AodMediaViewHolder")
                val ctor = vhCls.declaredConstructors.firstOrNull { it.parameterCount == 2 }
                    ?: throw NativeContract.Mismatch("missing AodMediaViewHolder 2-arg ctor")
                val titleField = vhCls.getDeclaredField("titleText").apply { isAccessible = true }
                val artistField = vhCls.getDeclaredField("artistText").apply { isAccessible = true }
                hook(ctor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val res = chain.proceed()
                    val holder = chain.thisObject
                    if (holder != null) {
                        val titleTv = titleField.get(holder) as? android.widget.TextView
                        val artistTv = artistField.get(holder) as? android.widget.TextView
                        runtime.bindAodMediaViews(titleTv, artistTv)
                    }
                    res
                }.also { runtime.aodHooked = true }
            }
            installOptional(hooks, "Lockscreen Seedling LyricData.getEnabledNotification hook") {
                val seedlingLyricCls = param.classLoader.loadClass("com.oplus.systemui.seedlingservice.mediaControl.LyricData")
                val getNotif = seedlingLyricCls.getDeclaredMethod("getEnabledNotification")
                hook(getNotif).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (runtime.isLockscreenBridgeEnabled()) true else chain.proceed()
                }
            }
            journal.note("Hook status: fluidCloud=${runtime.fluidCloudHooked}, lockscreen=${runtime.lockscreenHooked}, aod=${runtime.aodHooked}")
            journal.note("Required media publication hook installed: ${publication.declaringClass.name}#${publication.name}")
            Log.i(TAG, "SystemUI hooks ready")
            journal.note("SystemUI host hooks installed")
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            hooks.forEach { it.unhook() }
            report("Host contract", failure)
        }
    }

    private fun installMediaPublication(host: NativeContract.Host, runtime: CapsuleRuntime,
                                        hooks: MutableList<HookHandle>): Method {
        if (host.isSeedlingMediaMode) {
            val loaded = host.cos16MediaLoaded ?: throw NativeContract.Mismatch("missing ColorOS 16 onMediaDataLoaded")
            hooks += after(loaded) { instance, _, _ -> runtime.mediaChanged(instance, true, null) }
            host.cos16MediaRemoved?.let { removed ->
                installOptional(hooks, "ColorOS 16 onMediaDataRemoved hook") {
                    after(removed) { instance, _, _ -> runtime.mediaChanged(instance, true, null) }
                }
            }
            host.cos16UpdateSeedlings?.let { updated ->
                installOptional(hooks, "ColorOS 16 updateSeedlings hook") {
                    after(updated) { instance, _, _ -> runtime.mediaChanged(instance, true, null) }
                }
            }
            return loaded
        }
        host.send?.let { send ->
            try {
                hooks += after(send) { instance, _, result -> runtime.mediaChanged(instance, result == true, null) }
                return send
            } catch (failure: Throwable) {
                if (!isExpectedFailure(failure)) throw failure
                report("Primary media publication hook", failure)
            }
        }
        val dispatch = host.dispatchMethod()
        hooks += after(dispatch) { instance, args, result ->
            if (result == true) {
                if (args.firstOrNull() == "cancelLiveAlert") runtime.mediaReleased(instance)
                else (args.getOrNull(1) as? StatusBarNotification)?.let { runtime.mediaChanged(instance, true, it) }
            }
        }
        return dispatch
    }

    private fun installPlugin(plugin: NativeContract.Plugin, runtime: CapsuleRuntime): List<HookHandle> {
        val hooks = ArrayList<HookHandle>()
        try {
            hooks += after(plugin.draw) { receiver, args, _ ->
                (args.firstOrNull() as? Canvas)?.let { runtime.draw(receiver, it) }
            }
            diagnostics?.note("Required plugin hook installed: ${plugin.draw.declaringClass.name}#${plugin.draw.name}")
            plugin.measure?.let { method -> installOptional(hooks, "Capsule measure width hook") {
                before(method, "Capsule measure width") { receiver, _ ->
                    runtime.prepareCapsuleMeasure(receiver)
                }
            } }
            plugin.widthChange?.let { method -> installOptional(hooks, "Capsule width handoff hook") {
                after(method) { receiver, args, _ ->
                    if (args.firstOrNull() == true) runtime.nativeWidthAnimationStarted(receiver)
                }.also { runtime.viewportHookInstalled() }
            } }
            plugin.closeAnimation?.let { installMediaClose(hooks, it, runtime) }
            plugin.mediaCardScene?.let { installMediaCardScene(hooks, it, runtime) }
            plugin.animation?.let { animation -> installOptional(hooks, "Content animation hook") {
                hook(animation.contentFrame).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val restore = try {
                        chain.thisObject?.let(runtime::stabilizeMediaContentFrame)
                    } catch (failure: Throwable) {
                        if (!isExpectedFailure(failure)) throw failure
                        report("Media content layout", failure)
                        null
                    }
                    try {
                        chain.proceed()
                    } finally {
                        restore?.invoke()
                    }
                }
            } }
            plugin.contentChanged?.let { method -> installOptional(hooks, "Early content update hook") {
                after(method) { receiver, _, _ -> runtime.contentChanged(receiver) }
            } }
            plugin.layout?.let { method -> installOptional(hooks, "Plugin layout hook") {
                after(method) { receiver, _, _ -> runtime.containerLaidOut(receiver) }
            } }
            plugin.cos16FluidEvent?.let { fluidEvent -> installOptional(hooks, "ColorOS 16 capsule timeout event guard hook") {
                hook(fluidEvent.dispatch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val name = fluidEvent.eventName(chain.args.firstOrNull())
                    if (name == "EVENT_CAPSULE_TIME_OUT" && runtime.shouldPreventCapsuleCollapse()) {
                        diagnostics?.note("Blocked ColorOS 16 EVENT_CAPSULE_TIME_OUT")
                        null
                    } else {
                        chain.proceed()
                    }
                }
            } }
            plugin.cos16CapsuleTimer?.let { timer -> installOptional(hooks, "ColorOS 16 capsule 5-min timer guard hook") {
                val bubbleHandle = hook(timer.bubbleTimeout).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (runtime.shouldPreventCapsuleCollapse()) {
                        diagnostics?.note("Blocked ColorOS 16 capsuleToBubble mini-shrink callback")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                hooks += bubbleHandle
                val resetHandle = hook(timer.reset).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val key = chain.args.firstOrNull() as? String
                    val isBubbleOrMedia = key == "capsuleToBubble" || (key != null && key.startsWith("268452006"))
                    if (isBubbleOrMedia && runtime.shouldPreventCapsuleCollapse()) {
                        diagnostics?.note("Blocked ColorOS 16 5-min capsule timer reset ($key)")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                hooks += resetHandle
                hook(timer.schedule).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                    val key = chain.args.getOrNull(1) as? String
                    if (key != null && key.startsWith("268452006") && runtime.shouldBlockMediaCapsuleTimer()) {
                        diagnostics?.note("Blocked ColorOS 16 5-min capsule timer schedule ($key)")
                        null
                    } else {
                        chain.proceed()
                    }
                }
            } }
            return hooks
        } catch (failure: Throwable) {
            hooks.forEach { it.unhook() }
            throw failure
        }
    }

    private fun installMediaCardScene(hooks: MutableList<HookHandle>, scene: NativeContract.Plugin.MediaCardScene,
                                      runtime: CapsuleRuntime) {
        val installed = mutableListOf<HookHandle>()
        try {
            installed += before(scene.begin, "Media card viewport begin") { receiver, args ->
                val name = (args.firstOrNull() as? Enum<*>)?.name ?: return@before
                scene.container(receiver)?.let { runtime.beginMediaCardScene(it, name) }
            }
            installed += before(scene.end, "Media card viewport end") { receiver, _ ->
                scene.container(receiver)?.let(runtime::endMediaCardScene)
            }
            hooks += installed
            diagnostics?.note("Optional media card viewport handoff hooks installed")
        } catch (failure: Throwable) {
            installed.forEach { it.unhook() }
            if (!isExpectedFailure(failure)) throw failure
            report("Optional media card viewport handoff hooks", failure)
        }
    }

    private fun installMediaClose(hooks: MutableList<HookHandle>, contract: NativeContract.Plugin.CloseAnimation,
                                  runtime: CapsuleRuntime) {
        val building = ThreadLocal<NativeContract.Plugin.CloseAnimation.Shape>()
        var geometryHook: HookHandle? = null
        try {
            geometryHook = after(contract.buildGeometry) { helper, _, result ->
                val shape = building.get()?.takeIf { it.helper === helper } ?: return@after
                (result as? ValueAnimator)?.let { runtime.anchorMediaClose(shape, it) }
            }
            hooks += hook(contract.build).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
                val previous = building.get()
                val shape = try {
                    chain.thisObject?.let(contract::shape)
                } catch (failure: Throwable) {
                    if (!isExpectedFailure(failure)) throw failure
                    report("Media close geometry", failure)
                    null
                }
                building.set(shape)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) building.remove() else building.set(previous)
                }
            }
            hooks += geometryHook
            diagnostics?.note("Optional media close geometry hooks installed")
        } catch (failure: Throwable) {
            geometryHook?.unhook()
            if (!isExpectedFailure(failure)) throw failure
            report("Optional media close geometry hooks", failure)
        }
    }

    private inline fun installOptional(hooks: MutableList<HookHandle>, feature: String, install: () -> HookHandle) {
        try {
            hooks += install()
            diagnostics?.note("Optional $feature installed")
        } catch (failure: Throwable) {
            if (!isExpectedFailure(failure)) throw failure
            report("Optional $feature", failure)
        }
    }

    private fun optionalMissing(feature: String, failure: NativeContract.Mismatch) = report("Optional $feature", failure)

    private fun report(step: String, failure: Throwable) {
        reportFailure(step, failure)
        diagnostics?.failure(step, failure)
    }

    private fun before(method: Method, step: String, action: (Any, List<Any?>) -> Unit): HookHandle =
        hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            try {
                chain.thisObject?.let { action(it, chain.args) }
            } catch (failure: Throwable) {
                if (!isExpectedFailure(failure)) throw failure
                report(step, failure)
            }
            chain.proceed()
        }

    private fun after(method: Method, action: (Any, List<Any?>, Any?) -> Unit): HookHandle =
        hook(method).setExceptionMode(ExceptionMode.PROTECTIVE).intercept { chain ->
            val result = chain.proceed()
            try {
                chain.thisObject?.let { action(it, chain.args, result) }
            } catch (failure: Throwable) {
                if (!isExpectedFailure(failure)) throw failure
                report(method.name, failure)
            }
            result
        }
}

internal const val TAG = "TerraMoor"

internal fun isExpectedFailure(failure: Throwable): Boolean =
    failure is Exception || failure is LinkageError || failure is XposedFrameworkError

internal fun reportFailure(step: String, failure: Throwable) {
    diagnosticStack(step, failure).forEach { Log.w(TAG, it) }
}
