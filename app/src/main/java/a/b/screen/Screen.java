package a.b.screen;

import android.view.View;
import android.view.ViewGroup;
import a.b.MainActivity;

public interface Screen {
    View onCreateView(MainActivity activity, ViewGroup parent);
    View getView();
    void onResume();
    void onPause();
    void onDestroy();
    void onActivityResult(int requestCode, int resultCode, android.content.Intent data);
}