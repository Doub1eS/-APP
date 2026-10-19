package com.lianbei.daily

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName="food_entries",indices=[Index(value=["expenseId"],unique=true)])
data class FoodEntry(@PrimaryKey val foodId:String,val photoPath:String="",val expenseId:String?=null,val ownsExpense:Boolean=false)
