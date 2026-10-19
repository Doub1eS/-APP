package com.lianbei.daily
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.File
class FoodPhotosTest {
 @Test fun cleanupRemovesUnreferencedPhotosButKeepsSavedOnes(){
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val dir=File(c.filesDir,"food-photos").apply {mkdirs()}
  val previous=dir.listFiles().orEmpty().map {it.path}.toSet()
  val keep=File(dir,"keep-test.jpg").apply {writeBytes(byteArrayOf(1))}
  val discard=File(dir,"discard-test.jpg").apply {writeBytes(byteArrayOf(2))}
  try {FoodPhotos.clean(c,previous+keep.path);assertTrue(keep.exists());assertFalse(discard.exists())}
  finally {keep.delete();discard.delete()}
 }
 @Test fun importedPhotoSurvivesSourceRemovalAndCanBeExported(){
  val c=InstrumentationRegistry.getInstrumentation().targetContext
  val source=File(c.cacheDir,"food-photo-test.jpg")
  Bitmap.createBitmap(2400,1600,Bitmap.Config.ARGB_8888).let {b->source.outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,85,it)};b.recycle()}
  val path=FoodPhotos.import(c,Uri.fromFile(source))
  source.delete()
  try {assertTrue(File(path).exists());assertTrue(FoodPhotos.export(c,path).isNotBlank());val b=android.graphics.BitmapFactory.decodeFile(path);assertTrue(b.width<=1600);b.recycle()}
  finally {File(path).delete()}
 }
}
