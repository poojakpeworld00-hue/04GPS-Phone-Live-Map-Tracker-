package com.find.phone.gps.live.location.tracker.store.plus

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.text.NumberFormat
import java.util.Currency
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object PlusCheckout {
    private const val TAG = "PlusCheckout"
    private const val STORE = "premium"
    private const val FLAG_ACTIVE = "active"
    private const val PERIOD_UNITS = "YMWD"
    private val PERIOD_UNIT_DAYS = intArrayOf(365, 30, 7, 1)

    var onPurchaseResult: ((PurchaseOutcome) -> Unit)? = null

    enum class PurchaseOutcome { PURCHASED, PENDING, CANCELED, FAILED }

    private var client: BillingClient? = null
    private val details = HashMap<String, ProductDetails>()

    private val queued = ArrayList<(BillingClient?) -> Unit>()
    private var inFlight = false

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun isPremium(context: Context): Boolean = store(context).getBoolean(FLAG_ACTIVE, false)

    private fun writePremium(ctx: Context, active: Boolean) {
        store(ctx).edit().putBoolean(FLAG_ACTIVE, active).apply()
        Log.i(TAG, "premium=$active")
    }

    private fun billing(ctx: Context): BillingClient {
        val existing = client
        if (existing != null) return existing
        val appCtx = ctx.applicationContext
        val built = BillingClient.newBuilder(appCtx)
            .setListener { result, purchases -> handlePurchaseUpdate(appCtx, result, purchases) }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        client = built
        return built
    }

    private fun isOk(r: BillingResult) = r.responseCode == BillingClient.BillingResponseCode.OK

    private fun withClient(ctx: Context, block: (BillingClient?) -> Unit) {
        val bc = billing(ctx)
        if (bc.isReady) return block(bc)
        synchronized(this) {
            queued += block
            if (inFlight) return
            inFlight = true
        }
        bc.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val success = isOk(result)
                if (!success) Log.w(TAG, "setup failed: ${result.responseCode} ${result.debugMessage}")
                val drained = synchronized(this@PlusCheckout) {
                    inFlight = false
                    val copy = ArrayList(queued)
                    queued.clear()
                    copy
                }
                val handed = if (success) bc else null
                for (cb in drained) cb(handed)
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "service disconnected")
            }
        })
    }

    fun sync(context: Context, onDone: (Boolean?) -> Unit = {}) {
        val appCtx = context.applicationContext
        val configured = HashSet<String>()
        PlusProfile.read(appCtx).plans.forEach { configured.add(it.productId) }
        withClient(appCtx) { bc ->
            if (bc == null) return@withClient onDone(null)
            val gathered = ArrayList<Purchase>()
            val remaining = AtomicInteger(2)
            val anyFailure = AtomicBoolean(false)
            val onBatch = { success: Boolean, batch: List<Purchase> ->
                synchronized(gathered) { gathered += batch }
                if (!success) anyFailure.set(true)
                if (remaining.decrementAndGet() == 0) finishSync(appCtx, bc, gathered, configured, anyFailure.get(), onDone)
            }
            arrayOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP).forEach { type ->
                queryOwned(bc, type, onBatch)
            }
        }
    }

    private fun queryOwned(bc: BillingClient, type: String, onBatch: (Boolean, List<Purchase>) -> Unit) {
        bc.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, list ->
            val success = isOk(result)
            if (!success) Log.w(TAG, "queryPurchases($type): ${result.responseCode} ${result.debugMessage}")
            onBatch(success, list)
        }
    }

    private fun finishSync(
        appCtx: Context,
        bc: BillingClient,
        gathered: List<Purchase>,
        configured: Set<String>,
        failed: Boolean,
        onDone: (Boolean?) -> Unit,
    ) {
        if (failed) {
            Log.w(TAG, "sync incomplete — keeping premium=${isPremium(appCtx)}")
            onDone(null)
            return
        }
        val bought = gathered.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val active = bought.any { p -> p.products.any { id -> id in configured } }
        bought.filterNot { it.isAcknowledged }.forEach { ack(bc, it) }
        writePremium(appCtx, active)
        onDone(active)
    }

    fun fetchDetails(context: Context, plans: List<PlusOffer>, onLoaded: (Map<String, ProductDetails>) -> Unit) {
        withClient(context) { bc ->
            if (bc == null) return@withClient onLoaded(HashMap(details))
            val byType = plans.groupBy { plan ->
                when {
                    plan.isSubscription -> BillingClient.ProductType.SUBS
                    else -> BillingClient.ProductType.INAPP
                }
            }
            val remaining = AtomicInteger(byType.size)
            byType.forEach { (type, group) ->
                val query = QueryProductDetailsParams.newBuilder().setProductList(
                    group.map { plan ->
                        QueryProductDetailsParams.Product.newBuilder().setProductId(plan.productId).setProductType(type).build()
                    }
                ).build()
                bc.queryProductDetailsAsync(query) { result, res ->
                    if (isOk(result)) {
                        val loaded = res.productDetailsList
                        synchronized(details) { for (pd in loaded) details[pd.productId] = pd }
                        val loadedIds = loaded.mapTo(HashSet()) { it.productId }
                        val absent = group.map { it.productId }.filterNot { it in loadedIds }
                        when {
                            absent.isEmpty() -> Log.i(TAG, "productDetails($type): loaded ${loadedIds.sorted()}")
                            else -> Log.w(TAG, "productDetails($type): not found in Play: $absent")
                        }
                    } else {
                        Log.w(TAG, "productDetails($type): ${result.responseCode} ${result.debugMessage}")
                    }
                    if (remaining.decrementAndGet() == 0) onLoaded(synchronized(details) { HashMap(details) })
                }
            }
        }
    }

    fun prefetchDetails(context: Context) {
        val appCtx = context.applicationContext
        fetchDetails(appCtx, PlusProfile.read(appCtx).plans) {}
    }

    private fun cachedDetails(plan: PlusOffer): ProductDetails? = synchronized(details) { details[plan.productId] }

    private fun recurringPhase(pd: ProductDetails): ProductDetails.PricingPhase? =
        defaultOffer(pd)?.pricingPhases?.pricingPhaseList?.lastOrNull()

    fun price(plan: PlusOffer): String? {
        val pd = cachedDetails(plan) ?: return null
        return when (plan.isSubscription) {
            true -> recurringPhase(pd)?.formattedPrice
            false -> pd.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    fun weeklyPrice(plan: PlusOffer): String? {
        if (plan.kind != PlusOffer.Kind.MONTHLY) return null
        val pd = cachedDetails(plan) ?: return null
        val phase = recurringPhase(pd) ?: return null
        return try {
            val fmt = NumberFormat.getCurrencyInstance()
            fmt.currency = Currency.getInstance(phase.priceCurrencyCode)
            fmt.format(phase.priceAmountMicros / 4 / 1_000_000.0)
        } catch (t: Throwable) {
            null
        }
    }

    fun trialDays(plan: PlusOffer): Int? {
        if (!plan.isSubscription) return 0
        val pd = cachedDetails(plan) ?: return null
        val free = freeTrialOffer(pd)?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros == 0L }
            ?: return 0
        return periodToDays(free.billingPeriod) * maxOf(free.billingCycleCount, 1)
    }

    private fun freeTrialOffer(pd: ProductDetails): ProductDetails.SubscriptionOfferDetails? {
        val offers = pd.subscriptionOfferDetails ?: return null
        for (offer in offers) {
            if (offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }) return offer
        }
        return null
    }

    private fun periodToDays(period: String): Int {
        if (period.isEmpty() || period[0] != 'P') return 0
        var total = 0
        var lastRank = -1
        var pos = 1
        while (pos < period.length) {
            val numStart = pos
            while (pos < period.length && period[pos] in '0'..'9') pos++
            if (pos == numStart || pos == period.length) return 0
            val rank = PERIOD_UNITS.indexOf(period[pos])
            if (rank <= lastRank) return 0
            total += (period.substring(numStart, pos).toIntOrNull() ?: 0) * PERIOD_UNIT_DAYS[rank]
            lastRank = rank
            pos++
        }
        return total
    }

    fun purchase(activity: Activity, plan: PlusOffer): Boolean {
        val pd = cachedDetails(plan) ?: return false
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd)
        if (plan.isSubscription) {
            val offer = freeTrialOffer(pd) ?: defaultOffer(pd)
            val token = offer?.offerToken ?: return false
            productParams.setOfferToken(token)
        }
        val bc = client?.takeIf { it.isReady } ?: return false
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams.build())).build()
        val result = bc.launchBillingFlow(activity, flow)
        Log.i(TAG, "launchBillingFlow(${plan.productId}): ${result.responseCode} ${result.debugMessage}")
        return isOk(result)
    }

    private fun defaultOffer(pd: ProductDetails): ProductDetails.SubscriptionOfferDetails? {
        val offers = pd.subscriptionOfferDetails ?: return null
        return offers.find { it.offerId == null } ?: offers.firstOrNull()
    }

    private fun handlePurchaseUpdate(ctx: Context, result: BillingResult, purchases: List<Purchase>?) {
        val code = result.responseCode
        val outcome = if (code == BillingClient.BillingResponseCode.OK) {
            outcomeOf(ctx, purchases ?: emptyList())
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            writePremium(ctx, true)
            PurchaseOutcome.PURCHASED
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            PurchaseOutcome.CANCELED
        } else {
            PurchaseOutcome.FAILED
        }
        Log.i(TAG, "purchase update: ${result.responseCode} → $outcome")
        onPurchaseResult?.invoke(outcome)
    }

    private fun outcomeOf(ctx: Context, list: List<Purchase>): PurchaseOutcome {
        for (p in list) {
            if (p.purchaseState == Purchase.PurchaseState.PURCHASED && !p.isAcknowledged) {
                client?.let { ack(it, p) }
            }
        }
        val states = list.map { it.purchaseState }
        return when {
            Purchase.PurchaseState.PURCHASED in states -> {
                writePremium(ctx, true)
                PurchaseOutcome.PURCHASED
            }
            Purchase.PurchaseState.PENDING in states -> PurchaseOutcome.PENDING
            else -> PurchaseOutcome.FAILED
        }
    }

    private fun ack(bc: BillingClient, purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        bc.acknowledgePurchase(params) { r ->
            Log.i(TAG, "acknowledge ${purchase.products}: ${r.responseCode}")
        }
    }
}
