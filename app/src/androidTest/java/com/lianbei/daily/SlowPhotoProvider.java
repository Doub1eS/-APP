package com.lianbei.daily;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.graphics.Bitmap;
import java.io.FileNotFoundException;
public class SlowPhotoProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 public String getType(Uri uri){return "image/png";}
 public Cursor query(Uri u,String[] p,String s,String[] a,String sort){return null;}
 public Uri insert(Uri u,ContentValues v){return null;}
 public int delete(Uri u,String s,String[] a){return 0;}
 public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
 public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
  try {
   ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
   new Thread(()->{try {
    Thread.sleep(3000);
    Bitmap b=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
    try(ParcelFileDescriptor.AutoCloseOutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
   }catch(Exception ignored) {}}).start();
   return pipe[0];
  }catch(Exception e){throw new FileNotFoundException(e.getMessage());}
 }
}
