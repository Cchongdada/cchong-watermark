package com.cchong.watermark;
import java.util.*;
public final class FolderImportPlanTest {
    private static List<FolderImportPlan.Photo> photos(int count){List<FolderImportPlan.Photo> out=new ArrayList<>();for(int i=1;i<=count;i++)out.add(new FolderImportPlan.Photo("uri:"+i,"scan"+i+".JPG","image/jpeg"));return out;}
    private static void check(boolean good,String message){if(!good)throw new AssertionError(message);}
    public static void main(String[] args){
        check(!FolderImportPlan.create(photos(0),Collections.emptySet(),100).needsConfirmation(),"empty directory");
        check(!FolderImportPlan.create(photos(50),Collections.emptySet(),100).needsConfirmation(),"exactly 50 auto-imports");
        check(FolderImportPlan.create(photos(51),Collections.emptySet(),100).needsConfirmation(),"51 requires confirmation");
        FolderImportPlan big=FolderImportPlan.create(photos(123),Collections.emptySet(),100);
        check(big.totalPhotos==123&&big.toImport.size()==100&&big.exceedsCapacity&&big.needsConfirmation(),"over 100 exact count and cap");
        check(big.toImport.get(1).name.equals("scan2.JPG")&&big.toImport.get(99).name.equals("scan100.JPG"),"natural file order before cap");
        List<FolderImportPlan.Photo> mixed=photos(3);mixed.add(mixed.get(0));mixed.add(new FolderImportPlan.Photo("dir","subfolder.jpg","vnd.android.document/directory"));mixed.add(new FolderImportPlan.Photo("raw","scan.dng","image/x-adobe-dng"));mixed.add(new FolderImportPlan.Photo("note","info.txt","text/plain"));mixed.add(new FolderImportPlan.Photo("heic","scan4.HEIC","application/octet-stream"));
        FolderImportPlan clean=FolderImportPlan.create(mixed,Collections.singleton("uri:2"),100);
        check(clean.totalPhotos==4&&clean.duplicatePhotos==1&&clean.newPhotos==3,"directories and nonphotos excluded; dedup");
        check(clean.toImport.size()==3,"supported HEIC extension retained");
        FolderImportPlan remaining=FolderImportPlan.create(photos(36),Collections.emptySet(),20);
        check(remaining.needsConfirmation()&&remaining.toImport.size()==20,"small folder still confirms capacity shortfall");
        FolderImportPlan allExisting=FolderImportPlan.create(photos(1),Collections.singleton("uri:1"),100);
        check(allExisting.newPhotos==0&&allExisting.duplicatePhotos==1,"already imported folder");
        System.out.println("Folder import checks passed: 0/50/51/123, ordering, cap, supported files and duplicates");
    }
}
