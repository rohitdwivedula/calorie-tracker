package dev.rohit.labelaudit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
/** Small streaming USTAR writer. All entry names are app-generated ASCII paths. */
public final class TarWriter implements Closeable {
 private final OutputStream out;
 public TarWriter(OutputStream out)throws IOException{this.out=new GZIPOutputStream(out);}
 private static void field(byte[] h,int start,int len,String s){byte[] b=s.getBytes(StandardCharsets.US_ASCII);System.arraycopy(b,0,h,start,Math.min(b.length,len));}
 private static void oct(byte[] h,int start,int len,long n){field(h,start,len,String.format(java.util.Locale.ROOT,"%0"+(len-1)+"o",n));}
 public void add(String name,byte[] bytes)throws IOException{add(name,new ByteArrayInputStream(bytes),bytes.length);}
 public void add(String name,File f)throws IOException{try(InputStream in=new FileInputStream(f)){add(name,in,f.length());}}
 private void add(String name,InputStream in,long size)throws IOException {
  if(name.length()>99 || name.startsWith("/") || name.contains(".."))throw new IOException("Invalid archive path");
  byte[] h=new byte[512];field(h,0,100,name);oct(h,100,8,0644);oct(h,108,8,0);oct(h,116,8,0);oct(h,124,12,size);oct(h,136,12,System.currentTimeMillis()/1000);
  for(int i=148;i<156;i++)h[i]=32;h[156]='0';field(h,257,6,"ustar");field(h,263,2,"00");
  long sum=0;for(byte b:h)sum+=b&255;field(h,148,6,String.format(java.util.Locale.ROOT,"%06o",sum));h[154]=0;h[155]=32;out.write(h);
  byte[] buffer=new byte[32768];long remaining=size;
  while(remaining>0){int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException();out.write(buffer,0,n);remaining-=n;}
  out.write(new byte[(int)((512-size%512)%512)]);
 }
 public void close()throws IOException{out.write(new byte[1024]);out.close();}
}
