package com.lianbei.daily

object ExpenseCategories {
    val groups=linkedMapOf(
        "食品酒水" to listOf("早餐","午餐","晚餐","夜宵","饮料","零食"),
        "交通" to listOf("地铁","打车","电动车","公交","火车机票"),
        "居家物业" to listOf("日用品","水电","房租","物业"),
        "衣服" to listOf("服装","鞋帽","配饰"),
        "休闲娱乐" to listOf("运动健身","电影演出","游戏","旅行"),
        "医疗保健" to listOf("药品","保健","美容","治疗"),
        "学习进修" to listOf("数码装备","培训进修","书籍资料"),
        "人情往来" to listOf("礼物","红包","聚餐","孝敬家人"),
        "其他" to emptyList()
    )
    fun main(value:String)=when(value.trim()) {
        "餐饮"->"食品酒水"; "居家","居住","住房"->"居家物业"; "娱乐"->"休闲娱乐"
        ""->"其他";else->value.trim()
    }
    fun selection(parent:String,child:String):String {
        val normalized=main(parent)
        return if(child in groups[normalized].orEmpty()) "$normalized / $child" else normalized
    }
    fun foodMeal(meal:String)=meal.takeIf {it in groups.getValue("食品酒水")} ?: ""
}
