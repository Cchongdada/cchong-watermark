package com.cchong.watermark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Selection policy shared by directory import and its JVM checks. */
final class FolderImportPlan {
    static final class Photo {
        final String uri, name, mime;
        Photo(String uri,String name,String mime){this.uri=uri;this.name=name==null?"photo.jpg":name;this.mime=mime;}
    }
    final int totalPhotos, duplicatePhotos, newPhotos;
    final List<Photo> toImport;
    final boolean exceedsCapacity;
    private FolderImportPlan(int total,int duplicates,List<Photo> fresh,int capacity){
        totalPhotos=total;duplicatePhotos=duplicates;newPhotos=fresh.size();exceedsCapacity=fresh.size()>capacity;
        fresh.sort((a,b)->naturalCompare(a.name,b.name));
        toImport=Collections.unmodifiableList(new ArrayList<>(fresh.subList(0,Math.min(fresh.size(),Math.max(0,capacity)))));
    }
    boolean needsConfirmation(){return totalPhotos>50||exceedsCapacity;}
    static boolean supported(String name,String mime){
        if("vnd.android.document/directory".equals(mime))return false;
        String n=name==null?"":name.toLowerCase(Locale.ROOT);
        return n.matches(".*\\.(jpe?g|png|webp|heic|heif|avif)$") ||
            "image/jpeg".equals(mime)||"image/png".equals(mime)||"image/webp".equals(mime)||
            "image/heic".equals(mime)||"image/heif".equals(mime)||"image/avif".equals(mime);
    }
    static FolderImportPlan create(List<Photo> entries,Set<String> existing,int capacity){
        Set<String> seen=new HashSet<>();List<Photo> fresh=new ArrayList<>();int total=0,duplicate=0;
        for(Photo p:entries){if(!supported(p.name,p.mime)||!seen.add(p.uri))continue;total++;if(existing.contains(p.uri))duplicate++;else fresh.add(p);}
        return new FolderImportPlan(total,duplicate,fresh,capacity);
    }
    private static int naturalCompare(String a,String b){
        a=a.toLowerCase(Locale.ROOT);b=b.toLowerCase(Locale.ROOT);int i=0,j=0;
        while(i<a.length()&&j<b.length()){
            char x=a.charAt(i),y=b.charAt(j);
            if(Character.isDigit(x)&&Character.isDigit(y)){
                int ai=i,bj=j;while(i<a.length()&&Character.isDigit(a.charAt(i)))i++;while(j<b.length()&&Character.isDigit(b.charAt(j)))j++;
                String an=a.substring(ai,i).replaceFirst("^0+(?!$)",""),bn=b.substring(bj,j).replaceFirst("^0+(?!$)","");
                int order=Integer.compare(an.length(),bn.length());if(order==0)order=an.compareTo(bn);if(order!=0)return order;
            }else{if(x!=y)return Character.compare(x,y);i++;j++;}
        }
        int order=Integer.compare(a.length()-i,b.length()-j);return order!=0?order:a.compareTo(b);
    }
}
