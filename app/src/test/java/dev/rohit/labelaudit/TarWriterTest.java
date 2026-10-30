package dev.rohit.labelaudit;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
public class TarWriterTest {
 @Test public void archivePreservesUnicodeAndBlockAlignment()throws Exception {
  ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] text="Protein <1g · ½ cup".getBytes(StandardCharsets.UTF_8);byte[] photo=new byte[1025];for(int i=0;i<photo.length;i++)photo[i]=(byte)i;
  try(TarWriter tar=new TarWriter(output)){tar.add("ocr/test.txt",text);tar.add("photos/test.jpg",photo);}
  byte[] decoded;try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(output.toByteArray()))){decoded=in.readAllBytes();}
  assertEquals("ocr/test.txt",new String(decoded,0,12,StandardCharsets.US_ASCII));
  assertArrayEquals(text,java.util.Arrays.copyOfRange(decoded,512,512+text.length));
  assertEquals("photos/test.jpg",new String(decoded,1024,15,StandardCharsets.US_ASCII));
  assertArrayEquals(photo,java.util.Arrays.copyOfRange(decoded,1536,1536+photo.length));
  assertEquals(4096,decoded.length);
 }
 @Test(expected=IOException.class)public void rejectsUnsafePaths()throws Exception{try(TarWriter tar=new TarWriter(new ByteArrayOutputStream())){tar.add("../escape",new byte[0]);}}
}
