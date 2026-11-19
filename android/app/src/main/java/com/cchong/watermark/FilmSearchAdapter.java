package com.cchong.watermark;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FilmSearchAdapter extends BaseAdapter implements Filterable {
    private final Context context;
    private final List<String> source;
    private final Map<String,String> searchTerms;
    private List<String> matches;
    FilmSearchAdapter(Context context,List<String> rows,Map<String,String> searchTerms) {
        this.context=context; this.source=new ArrayList<>(rows); this.matches=new ArrayList<>(rows); this.searchTerms=searchTerms;
    }
    public int getCount(){return matches.size();}
    public String getItem(int position){return matches.get(position);}
    public long getItemId(int position){return position;}
    public View getView(int position,View convertView,ViewGroup parent){
        TextView text=convertView instanceof TextView?(TextView)convertView:new TextView(context);
        int pad=Math.round(12*context.getResources().getDisplayMetrics().density);
        text.setPadding(pad,pad,pad,pad);text.setTextSize(14);text.setTextColor(0xff242922);text.setMaxLines(3);text.setText(getItem(position));return text;
    }
    private static String key(String text){return text.toLowerCase(Locale.ROOT).replaceAll("[\\s!()'’-]", "");}
    public Filter getFilter(){return new Filter(){
        protected FilterResults performFiltering(CharSequence constraint){
            String query=key(constraint==null?"":constraint.toString());List<String> result=new ArrayList<>();
            for(String row:source){String terms=searchTerms!=null&&searchTerms.containsKey(row)?searchTerms.get(row):row;if(key(terms).contains(query))result.add(row);}
            FilterResults output=new FilterResults();output.values=result;output.count=result.size();return output;
        }
        @SuppressWarnings("unchecked") protected void publishResults(CharSequence constraint,FilterResults result){matches=(List<String>)result.values;notifyDataSetChanged();}
    };}
}
