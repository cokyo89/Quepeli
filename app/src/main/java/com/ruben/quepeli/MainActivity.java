package com.ruben.quepeli;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_CAMERA = 1;
    private static final int REQ_GALLERY = 2;
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private static final String PROMPT =
            "Eres un experto en cine y televisión. Esta imagen es una foto o captura de una pantalla "
            + "(tele, móvil u ordenador) mostrando una película o serie. Identifica qué película o serie es. "
            + "Fíjate en actores, personajes, decorados, subtítulos, logos de canal o plataforma y cualquier texto. "
            + "Responde SOLO con un JSON con este formato exacto: "
            + "{\"encontrado\": true/false, \"titulo\": \"título en España si existe\", "
            + "\"titulo_original\": \"título original\", \"tipo\": \"pelicula\" o \"serie\", "
            + "\"anio\": año de estreno como número o null, \"temporada\": número o null, \"episodio\": número o null, "
            + "\"actores\": [\"actores que reconozcas en la imagen\"], \"confianza\": número de 0 a 100, "
            + "\"pistas\": \"explicación breve en español de por qué lo crees\", "
            + "\"alternativas\": [\"otros títulos posibles\"]}. "
            + "Si no puedes identificarla, pon encontrado=false y explica en pistas qué se ve.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private Uri cameraUri;

    private ImageView preview;
    private ImageView poster;
    private TextView status;
    private TextView titleView;
    private TextView result;
    private Button tmdbButton;
    private Button cameraBtn;
    private Button galleryBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("quepeli", MODE_PRIVATE);
        if (savedInstanceState != null) {
            String u = savedInstanceState.getString("cameraUri");
            if (u != null) cameraUri = Uri.parse(u);
        }
        buildUi();

        if (!hasKeys()) {
            showSettings();
        }
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (cameraUri != null) outState.putString("cameraUri", cameraUri.toString());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ---------- Interfaz ----------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(32));
        scroll.addView(root);

        TextView header = new TextView(this);
        header.setText("¿Qué peli es?");
        header.setTextSize(28);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(header);

        TextView sub = new TextView(this);
        sub.setText("Haz una foto a la pantalla o elige una captura y te digo qué película o serie es.");
        sub.setTextSize(15);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        cameraBtn = new Button(this);
        cameraBtn.setText("📷 Hacer foto");
        cameraBtn.setOnClickListener(v -> openCamera());
        galleryBtn = new Button(this);
        galleryBtn.setText("🖼 Galería");
        galleryBtn.setOnClickListener(v -> openGallery());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(cameraBtn, lp);
        row.addView(galleryBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(220));
        preview.setPadding(0, dp(12), 0, dp(4));
        root.addView(preview, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        titleView = new TextView(this);
        titleView.setTextSize(22);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(titleView);

        poster = new ImageView(this);
        poster.setAdjustViewBounds(true);
        poster.setMaxHeight(dp(360));
        poster.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(poster, plp);

        result = new TextView(this);
        result.setTextSize(15);
        result.setTextIsSelectable(true);
        root.addView(result);

        tmdbButton = new Button(this);
        tmdbButton.setText("Ver ficha completa en TMDB");
        tmdbButton.setVisibility(View.GONE);
        root.addView(tmdbButton);

        Button settings = new Button(this);
        settings.setText("⚙ Ajustes (claves API)");
        settings.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(24);
        root.addView(settings, slp);

        setContentView(scroll);
    }

    private void setBusy(boolean busy) {
        cameraBtn.setEnabled(!busy);
        galleryBtn.setEnabled(!busy);
    }

    private void clearResult() {
        titleView.setText("");
        result.setText("");
        poster.setImageDrawable(null);
        tmdbButton.setVisibility(View.GONE);
    }

    // ---------- Ajustes ----------

    private boolean hasKeys() {
        return !prefs.getString("gemini", "").isEmpty();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView info = new TextView(this);
        info.setText("Clave de Gemini: aistudio.google.com/apikey\n"
                + "Clave de TMDB (opcional, para póster y ficha): themoviedb.org → Ajustes → API");
        info.setTextIsSelectable(true);
        box.addView(info);

        EditText gemini = new EditText(this);
        gemini.setHint("Clave API de Gemini");
        gemini.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        gemini.setText(prefs.getString("gemini", ""));
        box.addView(gemini);

        EditText tmdb = new EditText(this);
        tmdb.setHint("Clave API de TMDB (opcional)");
        tmdb.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        tmdb.setText(prefs.getString("tmdb", ""));
        box.addView(tmdb);

        EditText model = new EditText(this);
        model.setHint("Modelo (por defecto " + DEFAULT_MODEL + ")");
        model.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        model.setText(prefs.getString("model", DEFAULT_MODEL));
        box.addView(model);

        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setView(box)
                .setPositiveButton("Guardar", (d, w) -> {
                    String m = model.getText().toString().trim();
                    prefs.edit()
                            .putString("gemini", gemini.getText().toString().trim())
                            .putString("tmdb", tmdb.getText().toString().trim())
                            .putString("model", m.isEmpty() ? DEFAULT_MODEL : m)
                            .apply();
                    Toast.makeText(this, "Guardado", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- Entrada de imágenes ----------

    private void openCamera() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "quepeli_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QuePeli");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No encuentro una app de cámara", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la cámara: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Elige una imagen"), REQ_GALLERY);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No encuentro una galería", Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (extra instanceof Uri) {
            intent.setAction(null); // evitar reprocesar
            identify((Uri) extra);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            if (resultCode == RESULT_OK && cameraUri != null) {
                identify(cameraUri);
            } else if (cameraUri != null) {
                try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { }
                cameraUri = null;
            }
        } else if (requestCode == REQ_GALLERY && resultCode == RESULT_OK && data != null && data.getData() != null) {
            identify(data.getData());
        }
    }

    // ---------- Proceso principal ----------

    private void identify(Uri uri) {
        if (!hasKeys()) {
            Toast.makeText(this, "Primero pon tu clave de Gemini en Ajustes", Toast.LENGTH_LONG).show();
            showSettings();
            return;
        }
        clearResult();
        setBusy(true);
        status.setText("Analizando la imagen…");

        executor.submit(() -> {
            try {
                Bitmap bmp = loadScaled(uri, 1280);
                if (bmp == null) throw new Exception("No pude leer la imagen");
                runOnUiThread(() -> preview.setImageBitmap(bmp));

                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);

                JSONObject guess = askGemini(b64);
                showGuess(guess);

                String tmdbKey = prefs.getString("tmdb", "");
                if (guess.optBoolean("encontrado", false) && !tmdbKey.isEmpty()) {
                    runOnUiThread(() -> status.setText("Buscando la ficha…"));
                    lookupTmdb(guess, tmdbKey);
                }
                runOnUiThread(() -> {
                    if (status.getText().toString().endsWith("…")) status.setText("");
                    setBusy(false);
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                runOnUiThread(() -> {
                    status.setText("⚠️ " + msg);
                    setBusy(false);
                });
            }
        });
    }

    private Bitmap loadScaled(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o2);
        }
        if (b == null) return null;
        int big = Math.max(b.getWidth(), b.getHeight());
        if (big > maxSide) {
            float f = (float) maxSide / big;
            b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * f), Math.round(b.getHeight() * f), true);
        }
        return b;
    }

    // ---------- Gemini ----------

    private JSONObject askGemini(String b64) throws Exception {
        String key = prefs.getString("gemini", "");
        String model = prefs.getString("model", DEFAULT_MODEL);

        JSONObject inline = new JSONObject().put("mime_type", "image/jpeg").put("data", b64);
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", PROMPT))
                .put(new JSONObject().put("inline_data", inline));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)))
                .put("generationConfig", new JSONObject()
                        .put("response_mime_type", "application/json")
                        .put("temperature", 0.2));

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + URLEncoder.encode(model, "UTF-8") + ":generateContent";
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code == 429) throw new Exception("Has llegado al límite gratuito de Gemini por ahora. Prueba en un rato.");
        if (code == 400 || code == 403) throw new Exception("Gemini rechazó la petición (" + code + "). Revisa la clave en Ajustes.\n" + shortError(resp));
        if (code == 404) throw new Exception("Modelo no encontrado. Revisa el nombre del modelo en Ajustes.");
        if (code >= 400) throw new Exception("Error de Gemini (" + code + "): " + shortError(resp));

        JSONObject json = new JSONObject(resp);
        JSONArray cands = json.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) throw new Exception("Gemini no devolvió respuesta. Prueba con otra foto.");
        JSONArray outParts = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < outParts.length(); i++) {
            JSONObject p = outParts.getJSONObject(i);
            if (p.optBoolean("thought", false)) continue;
            text.append(p.optString("text", ""));
        }
        String t = text.toString().trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(json)?", "").replaceFirst("```$", "").trim();
        }
        int s = t.indexOf('{'), e = t.lastIndexOf('}');
        if (s >= 0 && e > s) t = t.substring(s, e + 1);
        return new JSONObject(t);
    }

    private String shortError(String resp) {
        try {
            return new JSONObject(resp).getJSONObject("error").optString("message", resp);
        } catch (Exception e) {
            return resp.length() > 200 ? resp.substring(0, 200) : resp;
        }
    }

    private void showGuess(JSONObject g) {
        StringBuilder sb = new StringBuilder();
        String title;
        if (g.optBoolean("encontrado", false)) {
            title = g.optString("titulo", g.optString("titulo_original", "¿?"));
            String orig = g.optString("titulo_original", "");
            String tipo = "serie".equalsIgnoreCase(g.optString("tipo")) ? "Serie" : "Película";
            sb.append(tipo);
            if (!g.isNull("anio") && g.optInt("anio", 0) > 0) sb.append(" · ").append(g.optInt("anio"));
            if (!orig.isEmpty() && !orig.equalsIgnoreCase(title)) sb.append("\nTítulo original: ").append(orig);
            if (!g.isNull("temporada") && g.optInt("temporada", 0) > 0) {
                sb.append("\nTemporada ").append(g.optInt("temporada"));
                if (!g.isNull("episodio") && g.optInt("episodio", 0) > 0) sb.append(", episodio ").append(g.optInt("episodio"));
            }
            sb.append("\nSeguridad: ").append(g.optInt("confianza", 0)).append("%");
            JSONArray act = g.optJSONArray("actores");
            if (act != null && act.length() > 0) sb.append("\nEn la imagen: ").append(join(act));
        } else {
            title = "No la he reconocido";
        }
        String pistas = g.optString("pistas", "");
        if (!pistas.isEmpty()) sb.append("\n\n").append(pistas);
        JSONArray alt = g.optJSONArray("alternativas");
        if (alt != null && alt.length() > 0) sb.append("\n\nOtras posibilidades: ").append(join(alt));

        final String fTitle = title;
        final String fText = sb.toString();
        runOnUiThread(() -> {
            titleView.setText(fTitle);
            result.setText(fText);
        });
    }

    private String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    // ---------- TMDB ----------

    private JSONObject tmdbGet(String path, String key) throws Exception {
        boolean bearer = key.length() > 60; // token de lectura v4
        String sep = path.contains("?") ? "&" : "?";
        String url = "https://api.themoviedb.org/3" + path + (bearer ? "" : sep + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        int code = c.getResponseCode();
        String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code == 401) throw new Exception("La clave de TMDB no es válida. Revísala en Ajustes.");
        if (code >= 400) throw new Exception("Error de TMDB (" + code + ")");
        return new JSONObject(resp);
    }

    private void lookupTmdb(JSONObject g, String key) {
        try {
            String wantType = "serie".equalsIgnoreCase(g.optString("tipo")) ? "tv" : "movie";
            int year = g.isNull("anio") ? 0 : g.optInt("anio", 0);
            String[] queries = { g.optString("titulo_original", ""), g.optString("titulo", "") };

            JSONObject best = null;
            for (String q : queries) {
                if (q.trim().isEmpty()) continue;
                JSONObject res = tmdbGet("/search/multi?language=es-ES&include_adult=false&query="
                        + URLEncoder.encode(q, "UTF-8"), key);
                best = pickBest(res.optJSONArray("results"), wantType, year);
                if (best != null) break;
            }
            if (best == null) {
                runOnUiThread(() -> status.setText("No encontré la ficha en TMDB."));
                return;
            }

            String type = best.optString("media_type", wantType);
            int id = best.getInt("id");
            String name = best.optString("movie".equals(type) ? "title" : "name", "");
            String date = best.optString("movie".equals(type) ? "release_date" : "first_air_date", "");
            String overview = best.optString("overview", "");
            double vote = best.optDouble("vote_average", 0);

            StringBuilder extra = new StringBuilder();
            if (vote > 0) extra.append("\n\n⭐ ").append(String.format(java.util.Locale.US, "%.1f", vote)).append("/10 en TMDB");
            if (date.length() >= 4) extra.append(" · ").append(date.substring(0, 4));
            if (!overview.isEmpty()) extra.append("\n\n").append(overview);

            String where = providers(type, id, key);
            if (!where.isEmpty()) extra.append("\n\n📺 Dónde verla en España:\n").append(where);

            Bitmap posterBmp = null;
            String pp = best.optString("poster_path", "");
            if (!pp.isEmpty() && !"null".equals(pp)) posterBmp = download("https://image.tmdb.org/t/p/w500" + pp);

            final Bitmap fPoster = posterBmp;
            final String fExtra = extra.toString();
            final String fName = name;
            final String link = "https://www.themoviedb.org/" + type + "/" + id + "?language=es-ES";
            runOnUiThread(() -> {
                if (!fName.isEmpty()) titleView.setText(fName);
                if (fPoster != null) poster.setImageBitmap(fPoster);
                result.append(fExtra);
                tmdbButton.setOnClickListener(v -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); } catch (Exception ignored) { }
                });
                tmdbButton.setVisibility(View.VISIBLE);
                status.setText("");
            });
        } catch (Exception e) {
            String msg = e.getMessage();
            runOnUiThread(() -> status.setText("⚠️ " + msg));
        }
    }

    private JSONObject pickBest(JSONArray results, String wantType, int year) {
        if (results == null) return null;
        JSONObject firstOfType = null;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String mt = r.optString("media_type");
            if (!"movie".equals(mt) && !"tv".equals(mt)) continue;
            if (!mt.equals(wantType)) continue;
            if (firstOfType == null) firstOfType = r;
            if (year > 0) {
                String d = r.optString("movie".equals(mt) ? "release_date" : "first_air_date", "");
                if (d.startsWith(String.valueOf(year))) return r;
            }
        }
        if (firstOfType != null) return firstOfType;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String mt = r.optString("media_type");
            if ("movie".equals(mt) || "tv".equals(mt)) return r;
        }
        return null;
    }

    private String providers(String type, int id, String key) {
        try {
            JSONObject res = tmdbGet("/" + type + "/" + id + "/watch/providers", key);
            JSONObject es = res.getJSONObject("results").optJSONObject("ES");
            if (es == null) return "";
            StringBuilder sb = new StringBuilder();
            appendProviders(sb, es.optJSONArray("flatrate"), "Suscripción");
            appendProviders(sb, es.optJSONArray("free"), "Gratis");
            appendProviders(sb, es.optJSONArray("ads"), "Gratis con anuncios");
            appendProviders(sb, es.optJSONArray("rent"), "Alquiler");
            appendProviders(sb, es.optJSONArray("buy"), "Compra");
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void appendProviders(StringBuilder sb, JSONArray arr, String label) {
        if (arr == null || arr.length() == 0) return;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            if (names.length() > 0) names.append(", ");
            names.append(p.optString("provider_name"));
        }
        sb.append("• ").append(label).append(": ").append(names).append("\n");
    }

    // ---------- Utilidades ----------

    private Bitmap download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            try (InputStream in = c.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
