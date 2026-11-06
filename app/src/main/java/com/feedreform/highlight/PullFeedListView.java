package com.feedreform.highlight;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ListView;
/** Pull refresh is recognized only when a gesture begins at the list's top. */
public class PullFeedListView extends ListView {
 interface Listener { void onPull(float progress); void onRelease(boolean ready); }
 private Listener listener; private float startX,startY; private boolean eligible,dragging,ready; private boolean refreshing; private boolean pullEnabled=true;
 public PullFeedListView(Context c,AttributeSet a){super(c,a);setOverScrollMode(OVER_SCROLL_NEVER);}
 void setPullListener(Listener l){listener=l;}
 void setRefreshing(boolean value){refreshing=value;}
 void setPullEnabled(boolean enabled){pullEnabled=enabled;if(!enabled){dragging=false;eligible=false;ready=false;animate().translationY(0f).setDuration(120).start();}}
 @Override public boolean dispatchTouchEvent(MotionEvent e){
  if(!pullEnabled){if(dragging){dragging=false;eligible=false;ready=false;animate().translationY(0f).setDuration(120).start();}return super.dispatchTouchEvent(e);}
  int action=e.getActionMasked();
  if(action==MotionEvent.ACTION_DOWN){startY=e.getY();startX=e.getX();eligible=!refreshing&&!canScrollVertically(-1);dragging=false;ready=false;}
  else if(action==MotionEvent.ACTION_MOVE&&eligible&&e.getPointerCount()==1){float dy=e.getY()-startY;float dx=Math.abs(e.getX()-startX);float slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
   if(!dragging&&dy>slop*2&&dy>dx*1.5f){dragging=true;MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();}
   if(dragging){float th=80*getResources().getDisplayMetrics().density;ready=dy>th;float progress=Math.min(1f,Math.max(0f,dy/th));setTranslationY(Math.min(dy*0.45f,th*1.2f));if(listener!=null)listener.onPull(progress);return true;}
  } else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){if(dragging){animate().translationY(0f).setDuration(180).start();if(listener!=null)listener.onRelease(action==MotionEvent.ACTION_UP&&ready);dragging=false;eligible=false;return true;}}
  return super.dispatchTouchEvent(e);
 }
}
