import java.net.URL;
import java.net.HttpURLConnection;

public class TestDownload {
    public static void main(String[] args) {
        try {
            String MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm";
            String finalUrl = MODEL_URL;
            HttpURLConnection connection = null;
            int redirects = 0;
            
            while (true) {
                URL url = new URL(finalUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.connect();
                
                int status = connection.getResponseCode();
                System.out.println("Status: " + status + " URL: " + finalUrl);
                if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                    status == HttpURLConnection.HTTP_MOVED_PERM ||
                    status == HttpURLConnection.HTTP_SEE_OTHER ||
                    status == 307 || status == 308) {
                    finalUrl = connection.getHeaderField("Location");
                    redirects++;
                    if (redirects > 10) throw new Exception("Too many redirects");
                } else {
                    break;
                }
            }
            
            long fileLength = connection.getContentLengthLong();
            System.out.println("Final file length: " + fileLength);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
