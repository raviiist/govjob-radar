package com.dhruvaxtechnologies.govjobradar.data

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 10-day free trial from install, then a one-time Google Play purchase unlocks the full app.
 * The trial starts at the first install time, so clearing data does not restart it.
 */
class Billing(private val context: Context, private val scope: CoroutineScope) : PurchasesUpdatedListener {
    companion object {
        const val PRODUCT_ID = "govjobradar_full_unlock"
        const val TRIAL_DAYS = 10
    }

    private val sp = context.getSharedPreferences("gjr_billing", Context.MODE_PRIVATE)
    private val _unlocked = MutableStateFlow(sp.getBoolean("unlocked", false))
    val unlocked: StateFlow<Boolean> = _unlocked
    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private var details: ProductDetails? = null

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    // ---- trial ----
    private val installedAt: Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    }.getOrDefault(System.currentTimeMillis())

    fun trialDaysLeft(now: Long = System.currentTimeMillis()): Int {
        val used = TimeUnit.MILLISECONDS.toDays(now - installedAt).toInt()
        return (TRIAL_DAYS - used).coerceAtLeast(0)
    }

    val trialActive: Boolean get() = trialDaysLeft() > 0

    // ---- Play Billing ----
    fun start() {
        if (client.isReady) { scope.launch { refresh() }; return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { refresh() }
            }
            override fun onBillingServiceDisconnected() { }
        })
    }

    suspend fun refresh() {
        if (!client.isReady) return
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP).build())).build()
        val pd = client.queryProductDetails(params)
        details = pd.productDetailsList?.firstOrNull()
        _price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
        val owned = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build())
        if (owned.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            val has = owned.purchasesList.any { PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            owned.purchasesList.forEach { handle(it) }
            if (!has && _unlocked.value) setUnlocked(false)   // refunded / revoked
        }
    }

    fun buy(activity: Activity) {
        val d = details
        if (d == null) {
            _message.value = "The store is not available right now. Check your internet connection and try again."
            start()
            return
        }
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build())).build()
        client.launchBillingFlow(activity, flow)
    }

    fun restore() {
        _message.value = null
        scope.launch {
            refresh()
            _message.value = if (_unlocked.value) "Purchase restored. Thank you!" else "No earlier purchase found for this Google account."
        }
    }

    fun clearMessage() { _message.value = null }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handle(it) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { refresh() }
            BillingClient.BillingResponseCode.USER_CANCELED -> { }
            else -> _message.value = "Purchase did not complete (${result.debugMessage.ifBlank { "code ${result.responseCode}" }})."
        }
    }

    private fun handle(p: Purchase) {
        if (PRODUCT_ID !in p.products) return
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                setUnlocked(true)
                if (!p.isAcknowledged) scope.launch {
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
                }
            }
            Purchase.PurchaseState.PENDING ->
                _message.value = "Payment pending. The app unlocks as soon as Google Play confirms it."
            else -> { }
        }
    }

    private fun setUnlocked(v: Boolean) {
        _unlocked.value = v
        sp.edit().putBoolean("unlocked", v).apply()
    }
}
