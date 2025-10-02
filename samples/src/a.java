package demo.obf;

public final class a {
  private static final String[] __={"ABCD","EFGH","IJKL","MNOP","QRST","UVWX","YZab","cdef","ghij","klmn","opqr","stuv","wxyz","0123","4567","89+/"};
  private static final char[] l1lI;
  static{
    StringBuilder S=new StringBuilder(64);
    for(String s:__){S.append(s);}
    l1lI=S.toString().toCharArray();
  }
  private static final int[] IIlI=new int[256];
  static{
    java.util.Arrays.fill(IIlI,-1);
    for(int i=0;i<l1lI.length;i++) IIlI[l1lI[i]]=i;
    IIlI['=']=-2; IIlI['\n']=IIlI['\r']=IIlI['\t']=IIlI[' ']=-3;
  }
  public static String O0O0(byte[] q){
    if(q==null||q.length==0)return "";
    StringBuilder sb=new StringBuilder(((q.length+2)/3)*4);
    int i=0;
    while(i<q.length){
      int b0=q[i++]&255, b1=(i<q.length?q[i]&255:0), b2=(i+1<q.length?q[i+1]&255:0);
      int c0=b0>>>2, c1=((b0&3)<<4)|(b1>>>4), c2=((b1&15)<<2)|(b2>>>6), c3=b2&63;
      sb.append(l1lI[c0]); sb.append(l1lI[c1]);
      sb.append(i<q.length?(i+1<q.length?l1lI[c2&63]:'='):'=');
      sb.append(i+1<q.length?l1lI[c3]:'=');
      i+=2;
    }
    return sb.toString();
  }
  public static byte[] o0O0(String z){
    if(z==null||z.isEmpty())return new byte[0];
    char[] ch=z.toCharArray(); int len=0,pad=0;
    for(char x:ch){int v=x<256?IIlI[x]:-1; if(v==-3)continue; if(x=='=')pad++; if(v>=0||x=='=')len++;}
    if(len==0)return new byte[0];
    int groups=len/4, outLen=groups*3-Math.max(0,pad); byte[] out=new byte[outLen];
    int acc=0,cnt=0,o=0;
    for(char x:ch){
      if(x>=256)continue; int v=IIlI[x];
      if(v==-3)continue; if(x=='=')v=-2;
      if(v>=0){
        acc=(acc<<6)|v; if(++cnt==4){
          out[o++]=(byte)((acc>>16)&255);
          if(o<outLen)out[o++]=(byte)((acc>>8)&255);
          if(o<outLen)out[o++]=(byte)(acc&255);
          acc=0;cnt=0;
        }
      }else if(v==-2){
        if(cnt==2){acc<<=12; out[o++]=(byte)((acc>>16)&255);}
        else if(cnt==3){acc<<=6; out[o++]=(byte)((acc>>16)&255); if(o<outLen)out[o++]=(byte)((acc>>8)&255);}
        break;
      }
    }
    return out;
  }
}