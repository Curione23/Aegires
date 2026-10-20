package a.b;

import android.app.Application;
import org.conscrypt.Conscrypt;
import java.security.Security;

public class AegiresApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Security.insertProviderAt(Conscrypt.newProvider(), 1);
        } catch (Throwable ignored) {}
    }
}