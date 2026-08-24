package ru.zoommax;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.witvpn.ikev2.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public class TelegramAD extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImageView imageView = findViewById(R.id.image);
        TextView textView = findViewById(R.id.texthead);
        TextView textView1 = findViewById(R.id.textbody);
        Button button = findViewById(R.id.go);
        String header = "", body = "", picName = "", url = "";
        try {
            String json = new webget("http://url:25325/api/v1/getad").get();
            JSONObject jsonObject = new JSONObject(json);
            header = jsonObject.getString("head");
            body = jsonObject.getString("text");
            url = jsonObject.getString("url");
            picName = jsonObject.getString("pic");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        if (!picName.equals("")){
            Glide.with(this).load("http://url:25325/api/v1/getpic?" + picName).into(imageView);
        }
        textView.setText(header);
        textView1.setText(body);
        String finalUrl = url;
        button.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(finalUrl));
            startActivity(i);
        });
    }
}

class webget {
    String urls;

    public webget(String urls) {
        this.urls = urls;
    }

    public String get() {
        ExecutorService executor = Executors.newFixedThreadPool(1);
        Callable<String> callable = new Callable<String>() {
            @Override
            public String call() throws Exception {
                try {
                    URL url = new URL(urls);
                    HttpURLConnection con = (HttpURLConnection) url.openConnection();
                    if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
                        //Если запрос выполнен удачно, читаем полученные данные и далее, делаем что-то
                        String res = new BufferedReader(new InputStreamReader(con.getInputStream())).readLine();
                        return res;
                    } else {
                        //Если запрос выполнен не удачно, делаем что-то другое

                        return con.getResponseMessage();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return "error;" + e.getMessage() + ";";
                }
            }
        };
        FutureTask<String> future = (FutureTask) executor.submit(callable);
        try {
            return future.get();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
            return "error;" + e.getMessage() + ";";
        }
    }
}
