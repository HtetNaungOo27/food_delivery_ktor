package com.codewithfk.model

import kotlinx.serialization.Serializable

@Serializable data class PayoutAccount(val bankName: String, val accountName: String, val accountNumberMasked: String)
@Serializable data class SavePayoutAccountRequest(val bankName: String, val accountName: String, val accountNumber: String)
@Serializable data class PayoutItem(val id: String, val amount: Double, val status: String, val reference: String?, val requestedAt: String, val processedAt: String?)
@Serializable data class PayoutOverview(val account: PayoutAccount?, val availableBalance: Double, val history: List<PayoutItem>)
@Serializable data class RequestPayout(val amount: Double)
@Serializable data class ProcessPayoutRequest(val status: String, val reference: String? = null)
