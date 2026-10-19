package com.lianbei.daily

import androidx.room.Entity
import androidx.room.PrimaryKey

// Hashed notification/transaction identifiers only; no notification text is stored.
@Entity(tableName="payment_identities")
data class PaymentIdentity(@PrimaryKey val identity:String,val paymentId:String)
