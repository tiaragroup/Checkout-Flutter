package com.example.checkout_flutter

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.bumptech.glide.Glide
import company.tap.tapcheckout_android.CheckoutConfiguration
import company.tap.tapcheckout_android.R as TapR
import company.tap.tapcheckout_android.TapCheckout
import company.tap.tapcheckout_android.TapCheckoutStatusDelegate
import company.tap.tapcheckout_android.threeDsWebview.ThreeDsBottomSheetFragmentButton
import company.tap.tapcheckout_android.threeDsWebview.ThreeDsWebViewActivityButton
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Bridges the `checkout_flutter` method channel to Checkout-Android: `startCheckout` adds a full-screen
 * `TapCheckout` view over the Flutter activity, and the delegate callbacks are sent back to Dart with
 * the same names the iOS plugin uses (onReady, onChargeCreated, onSuccess, onError, onClose).
 */
class CheckoutFlutterPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
  private var channel: MethodChannel? = null
  private var activity: Activity? = null
  private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

  /** The view of the checkout that is on screen, if any. */
  private var checkoutView: TapCheckout? = null

  /**
   * The checkout page takes seconds to load (config request, then the page) and draws its own loader
   * only part way, so the app looked stuck and then showed two loaders. The checkout is kept see-through
   * (alpha 0, still laid out and loading) under this loader until onReady, then faded in: one loader.
   */
  private var checkoutLoader: View? = null

  /** A 3DS activity created but not started yet, and the loader shown over it until its sheet appears. */
  private var pendingThreeDs: ThreeDsWebViewActivityButton? = null
  private var threeDsLoader: View? = null

  /**
   * Checkout-Android loads the 3DS page in a transparent activity with nothing on screen and shows its
   * sheet only 5s after the page finished loading, so the app looks stuck. A loader covers that gap.
   * It is added on start (the SDK calls setContentView after onActivityCreated) and only once, so it
   * does not come back behind the sheet when the app returns from the background.
   */
  private val threeDsWatcher = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
      if (activity !is ThreeDsWebViewActivityButton) return
      pendingThreeDs = activity
      activity.supportFragmentManager.registerFragmentLifecycleCallbacks(threeDsSheetWatcher, false)
    }
    override fun onActivityStarted(activity: Activity) {
      if (activity !== pendingThreeDs) return
      pendingThreeDs = null
      showThreeDsLoader(activity)
    }
    override fun onActivityDestroyed(activity: Activity) {
      if (pendingThreeDs === activity) pendingThreeDs = null
      if (threeDsLoader?.context === activity) threeDsLoader = null
    }
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
  }

  private val threeDsSheetWatcher = object : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
      if (f is ThreeDsBottomSheetFragmentButton) hideThreeDsLoader()
    }
  }

  /** A dimmed, touch-blocking screen with the loader GIF Checkout-Android ships, so it looks like the SDK's own. */
  private fun loaderView(activity: Activity): View = FrameLayout(activity).apply {
    setBackgroundColor(Color.argb(153, 0, 0, 0))
    isClickable = true
    val size = (48 * resources.displayMetrics.density).toInt()
    addView(
      ImageView(activity).also { Glide.with(activity).asGif().load(TapR.drawable.loader).into(it) },
      FrameLayout.LayoutParams(size, size, Gravity.CENTER),
    )
  }

  private fun showThreeDsLoader(activity: Activity) {
    val loader = loaderView(activity)
    activity.addContentView(
      loader,
      ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    threeDsLoader = loader
  }

  private fun hideThreeDsLoader() {
    threeDsLoader?.let { loader -> (loader.parent as? ViewGroup)?.removeView(loader) }
    threeDsLoader = null
  }

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(binding.binaryMessenger, "checkout_flutter").also { it.setMethodCallHandler(this) }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel?.setMethodCallHandler(null)
    channel = null
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "startCheckout" -> startCheckout(call, result)
      else -> result.notImplemented()
    }
  }

  private fun startCheckout(call: MethodCall, result: Result) {
    val configurations = call.argument<Map<String, Any?>>("configurations")
    if (configurations == null) {
      result.error("INVALID_ARGUMENT", "Configurations are required", null)
      return
    }
    val publicKey = CheckoutArguments.publicKey(configurations)
    if (publicKey.isNullOrEmpty()) {
      result.error("INVALID_CONFIGURATION", "gateway.publicKey is required", null)
      return
    }
    val currentActivity = activity
    if (currentActivity == null) {
      result.error("NO_ACTIVITY", "No activity available", null)
      return
    }

    try {
      // A checkout that is still open is replaced by the new one.
      removeCheckoutView()
      val view = TapCheckout(currentActivity)
      val content = currentActivity.findViewById<ViewGroup>(android.R.id.content)
      content.addView(
        view,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
      )
      // The view stays until the flow ends. Not INVISIBLE: the page must keep loading and drawing.
      view.visibility = View.VISIBLE
      view.alpha = 0f
      checkoutView = view
      checkoutLoader = loaderView(currentActivity).also {
        content.addView(
          it,
          ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
      }

      CheckoutConfiguration.configureWithTapCheckoutDictionary(
        currentActivity,
        publicKey,
        view,
        CheckoutArguments.configuration(configurations),
        Delegate(view),
      )
      result.success(true)
    } catch (e: Exception) {
      removeCheckoutView()
      result.error("CHECKOUT_ERROR", "Failed to start checkout: ${e.message}", e.toString())
    }
  }

  /**
   * One delegate per checkout. Once the flow has ended (success, error or close) its view is removed
   * and later events are dropped: the page can still report a stale "Card loading timeout" error
   * minutes after a success.
   */
  private inner class Delegate(private val view: TapCheckout) : TapCheckoutStatusDelegate {
    private var finished = false

    private fun send(method: String, data: String? = null, ends: Boolean = false) = mainHandler.post {
      if (finished) return@post
      if (ends) {
        finished = true
        if (checkoutView === view) removeCheckoutView()
      }
      channel?.invokeMethod(method, data?.let { mapOf("data" to it) })
    }

    override fun onCheckoutReady() {
      mainHandler.post {
        if (checkoutView !== view) return@post
        hideCheckoutLoader()
        view.animate().alpha(1f).setDuration(200L)
      }
      send("onReady")
    }
    override fun onCheckoutChargeCreated(data: String) { send("onChargeCreated", data) }
    override fun onCheckoutSuccess(data: String) { send("onSuccess", data, ends = true) }
    override fun onCheckoutError(error: String) { send("onError", error, ends = true) }
    override fun onCheckoutcancel() { send("onClose", ends = true) }
  }

  private fun removeCheckoutView() {
    hideCheckoutLoader()
    checkoutView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
    checkoutView = null
  }

  private fun hideCheckoutLoader() {
    checkoutLoader?.let { loader -> (loader.parent as? ViewGroup)?.removeView(loader) }
    checkoutLoader = null
  }

  // ActivityAware

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.activity.application.registerActivityLifecycleCallbacks(threeDsWatcher)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    activity?.application?.unregisterActivityLifecycleCallbacks(threeDsWatcher)
    activity = null
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    activity = binding.activity
    binding.activity.application.registerActivityLifecycleCallbacks(threeDsWatcher)
  }

  override fun onDetachedFromActivity() {
    removeCheckoutView()
    activity?.application?.unregisterActivityLifecycleCallbacks(threeDsWatcher)
    activity = null
  }
}

/** Turns the arguments of `startCheckout` into what Checkout-Android expects. Plain JVM, so it is unit tested. */
internal object CheckoutArguments {

  fun publicKey(configurations: Map<String, Any?>): String? =
    (configurations["gateway"] as? Map<*, *>)?.get("publicKey") as? String

  /**
   * The SDK takes a `HashMap<String, Any>` whose nested objects are maps and lists, which is what the
   * method channel delivers. Only `null` values have to go: the map cannot hold them.
   */
  fun configuration(configurations: Map<String, Any?>): HashMap<String, Any> {
    val result = HashMap<String, Any>()
    for ((key, value) in configurations) {
      clean(value)?.let { result[key] = it }
    }
    return result
  }

  private fun clean(value: Any?): Any? = when (value) {
    null -> null
    is Map<*, *> -> LinkedHashMap<String, Any>().apply {
      for ((key, nested) in value) clean(nested)?.let { put(key.toString(), it) }
    }
    is List<*> -> value.mapNotNull { clean(it) }
    else -> value
  }
}
