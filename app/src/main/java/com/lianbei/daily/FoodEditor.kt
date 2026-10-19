package com.lianbei.daily

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable fun FoodEditor(day:String,record:DailyRecord?,info:FoodEntry?,records:List<DailyRecord>,foodEntries:List<FoodEntry>,subscriptionPayments:List<SubscriptionPayment>,busy:Boolean,saveError:String,onDismiss:()->Unit,onSave:(DailyRecord,String,Long?,String?)->Unit) {
 val context=LocalContext.current
 val id=rememberSaveable {record?.id ?: UUID.randomUUID().toString()}
 var meal by rememberSaveable {mutableStateOf(record?.title ?: when(LocalTime.now().hour){in 5..10->"早餐";in 11..15->"午餐";in 16..21->"晚餐";else->"加餐"})}
 var text by rememberSaveable {mutableStateOf(record?.detail ?: "")}
 var date by rememberSaveable {mutableStateOf(record?.date ?: day)}
 var time by rememberSaveable {mutableStateOf(record?.time ?: LocalTime.now().toString().take(5))}
 var photo by rememberSaveable {mutableStateOf(info?.photoPath ?: "")}
 var amount by rememberSaveable {mutableStateOf(records.find {it.id==info?.expenseId}?.value?.let {RecordRules.money(it)} ?: "")}
 var existing by rememberSaveable {mutableStateOf(info?.takeUnless {it.ownsExpense}?.expenseId)}
 var choosing by rememberSaveable {mutableStateOf(false)}
 var pendingImport by rememberSaveable {mutableStateOf<String?>(null)}
 var pendingCapture by rememberSaveable {mutableStateOf(false)}
 val importing=pendingImport!=null
 var error by remember {mutableStateOf("")}
 var capture by rememberSaveable {mutableStateOf<String?>(null)}
 fun importPhoto(uri:Uri,cleanup:Boolean=false) {
  pendingCapture=cleanup;pendingImport=uri.toString();error=""
  if(!cleanup)runCatching {context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
 }
 LaunchedEffect(pendingImport) {
  val uri=pendingImport?.let {Uri.parse(it)} ?: return@LaunchedEffect
  var cancelled=false
  try {
   val path=withContext(Dispatchers.IO){FoodPhotos.import(context,uri)}
   FoodPhotos.discardDraft(context,photo);photo=path
  }catch(e:CancellationException){cancelled=true;throw e}
  catch(_:Exception){error="读取照片失败，请选择30MB以内的有效图片。"}
  finally {
   if(!cancelled){
    if(pendingCapture)runCatching {context.contentResolver.delete(uri,null,null)}
    else runCatching {context.contentResolver.releasePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)}
    pendingImport=null
   }
  }
 }
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)importPhoto(uri)}
 val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){success->
  capture?.let {value->val uri=Uri.parse(value);if(success)importPhoto(uri,true) else runCatching {context.contentResolver.delete(uri,null,null)}}
  capture=null
 }
 val linked=records.find {it.id==existing}
 val used=foodEntries.filter {it.foodId!=id}.mapNotNull {it.expenseId}.toSet()+subscriptionPayments.map {it.recordId}
 SubscriptionDialog(if(record==null)"记一笔 · 饮食" else "编辑 · 饮食",busy||importing,error.ifBlank {saveError},{FoodPhotos.discardDraft(context,photo);onDismiss()}, {
  val cents=if(amount.isBlank())null else RecordRules.cents(amount)
  val parsedTime=runCatching {LocalTime.parse(time)}.getOrNull()
  error=when {
   LocalDate.parse(date)>LocalDate.now()->"日期不能晚于今天"
   parsedTime==null->"请填写有效时间，例如 12:00"
   existing!=null && linked==null->"所选消费已删除，请解除关联后重试"
   existing==null&&amount.isNotBlank()&&(cents==null||cents>10000000000L)->"价格需大于零且不超过1亿元，最多两位小数"
   else->""
  }
  if(error.isBlank())onSave(DailyRecord(id,"FOOD",date,parsedTime.toString().take(5),meal,text.trim(),createdAt=record?.createdAt ?: System.currentTimeMillis()),photo,if(existing==null)cents else null,existing)
 },"保存记录") {
  Text("餐次、照片、价格一次记好。吃了什么和照片都可以不填。")
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("早餐","午餐","晚餐","加餐").forEach {m->FilterChip(meal==m,{meal=m},label={Text(m)})}}
  OutlinedTextField(text,{text=it},label={Text("吃了什么（可选）")},modifier=Modifier.fillMaxWidth(),minLines=2)
  FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   OutlinedButton(enabled=!importing&&!busy,onClick={try {val uri=FoodPhotos.captureUri(context);capture=uri.toString();camera.launch(uri)}catch(_:Exception){error="无法打开相机，请使用相册选图。"}}){Text("拍照")}
   OutlinedButton(enabled=!importing&&!busy,onClick={try {picker.launch(arrayOf("image/*"))}catch(_:Exception){error="无法打开图片选择器"}}){Text("从相册选图")}
  }
  if(importing)Text("正在保存照片…")
  if(photo.isNotBlank()){FoodPhoto(photo);TextButton(onClick={FoodPhotos.discardDraft(context,photo);photo=""},enabled=!busy&&!importing){Text("移除照片")}}
  if(existing==null) {
   OutlinedTextField(amount,{amount=it},label={Text("价格（元，可选）")},modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
   Text(if(info?.ownsExpense==true)"改价会同步更新关联消费；清空价格只解除关联，原消费仍保留。" else "填写价格后自动计入餐饮消费。留空表示未记录价格。",style=MaterialTheme.typography.bodySmall)
  } else {
   Text("已关联：${linked?.date ?: ""} · ${linked?.title ?: "消费已删除"} · ¥${RecordRules.money(linked?.value ?: 0)}")
   Text("保留这笔消费的金额和日期；需要修改时请到消费记录编辑。",style=MaterialTheme.typography.bodySmall)
   TextButton(onClick={existing=null;amount=""}){Text("解除关联（保留消费）")}
  }
  TextButton(onClick={choosing=!choosing}){Text("关联已有消费")}
  if(choosing) {
   if(info?.ownsExpense==true)Text("改为关联已有消费时，会移除原来自动生成的消费，避免重复。")
   val choices=records.filter {it.kind=="EXPENSE"&&it.id !in used&&it.id!=info?.expenseId}
   if(choices.isEmpty())Text("暂无可关联消费；支付通知请先在「支付待确认」确认入账。")
   choices.forEach {r->OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={existing=r.id;choosing=false}){Text("${r.date} · ${r.title} · ¥${RecordRules.money(r.value ?: 0)} · ${r.detail}")}}
  }
  SubscriptionDate("日期",date){date=it}
  OutlinedTextField(time,{time=it},label={Text("记录时间 HH:mm")},modifier=Modifier.fillMaxWidth(),singleLine=true)
 }
}
