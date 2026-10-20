package com.lianbei.daily

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object FoodPhotos {
 private val lifecycleLock=Mutex()
 suspend fun <T> withLifecycleLock(block:suspend ()->T):T =
  withContext(Dispatchers.IO) { lifecycleLock.withLock { block() } }
 suspend fun cleanStored(context:Context,dao:RecordDao) = withLifecycleLock {
  clean(context,dao.allFoodEntries().map {it.photoPath}.toSet())
 }
 fun clean(context:Context,keep:Set<String>) {
  val dir=File(context.filesDir,"food-photos").canonicalFile
  val paths=keep.filter {it.isNotBlank()}.map {File(it).canonicalPath}.toSet()
  dir.listFiles().orEmpty().filter {it.isFile&&it.canonicalFile.parentFile==dir&&it.canonicalPath !in paths}.forEach {it.delete()}
 }
 fun discardDraft(context:Context,path:String) {
  val f=File(path).canonicalFile
  if(f.parentFile==File(context.cacheDir,"food-drafts").canonicalFile)f.delete()
 }
 fun commit(context:Context,path:String):String {
  if(path.isBlank())return ""
  val source=requireNotNull(file(context,path)){"照片已不可用，请重新选择"}
  val dir=File(context.filesDir,"food-photos").apply {mkdirs()}.canonicalFile
  if(source.parentFile==dir)return source.path
  return source.copyTo(File(dir,source.name),overwrite=true).path
 }
 fun captureUri(context:Context):Uri {
  val dir=File(context.cacheDir,"food-capture").apply {mkdirs()}
  return FileProvider.getUriForFile(context,"${context.packageName}.photos",File(dir,"${UUID.randomUUID()}.jpg"))
 }
 fun import(context:Context,uri:Uri):String {
  val source=File.createTempFile("food-import-",".img",context.cacheDir)
  try {
   context.contentResolver.openInputStream(uri).use {input->
    requireNotNull(input){"无法读取照片"};source.outputStream().use {output->
     val buffer=ByteArray(8192);var total=0L
     while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=30L*1024*1024){"照片超过30MB"};output.write(buffer,0,count)}
    }
   }
   val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(source.path,bounds)
   require(bounds.outWidth>0&&bounds.outHeight>0){"请选择有效图片"}
   var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600)sample*=2
   val decoded=requireNotNull(BitmapFactory.decodeFile(source.path,BitmapFactory.Options().apply {inSampleSize=sample}))
   val orientation=runCatching {ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
   val matrix=Matrix().apply {when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)}}
   val bitmap=Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true)
   val dir=File(context.cacheDir,"food-drafts").apply {mkdirs()};val target=File(dir,"${UUID.randomUUID()}.jpg")
   try {target.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it))};return target.path}
   finally {if(bitmap!==decoded)bitmap.recycle();decoded.recycle()}
  }finally {source.delete()}
 }
 fun file(context:Context,path:String):File?=runCatching {
  File(path).canonicalFile.takeIf {val parent=it.parentFile;parent!=null && parent in listOf(File(context.filesDir,"food-photos").canonicalFile,File(context.cacheDir,"food-drafts").canonicalFile)&&it.isFile}
 }.getOrNull()
 fun export(context:Context,path:String):String=if(path.isBlank())"" else Base64.encodeToString(requireNotNull(file(context,path)){"照片文件缺失"}.readBytes(),Base64.NO_WRAP)
}

@Composable fun FoodPhoto(path:String) {
 if(path.isBlank())return
 val context=LocalContext.current
 val bitmap by produceState<Bitmap?>(null,path) {value=withContext(Dispatchers.IO){FoodPhotos.file(context,path)?.let {BitmapFactory.decodeFile(it.path)}}}
 var expanded by remember {mutableStateOf(false)}
 if(bitmap!=null) {
  Image(bitmap!!.asImageBitmap(),"饮食照片，点击放大",Modifier.fillMaxWidth().height(180.dp).clickable {expanded=true},contentScale=ContentScale.Crop)
  if(expanded)Dialog(onDismissRequest={expanded=false}) {Surface {Column(Modifier.padding(12.dp)){
   Image(bitmap!!.asImageBitmap(),"饮食照片",Modifier.fillMaxWidth().heightIn(max=500.dp),contentScale=ContentScale.Fit)
   TextButton(onClick={expanded=false}){Text("关闭照片")}
  }}}
 }else Text("照片加载中或文件已不可用",style=MaterialTheme.typography.bodySmall)
}
