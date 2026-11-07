package a.b.screen;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import a.b.MainActivity;
import a.b.model.Message;
import a.b.util.Crypto;

/** E2EE helpers: prepare outgoing text, decrypt incoming messages in place. */
final class MessageCryptoHelper {
    private static final Pattern SIZE_TAG_PATTERN = Pattern.compile("^\\[sz:(\\d+),(\\d+)\\]");

    private MessageCryptoHelper() {}

    static String prepareOutgoingText(MessageListScreen host, String plain) {
        if (host == null || plain == null) return null;
        if (!host.encOn()) return plain;
        SecretKey key = host.key();
        MainActivity activity = host.activity;
        if (key == null) {
            if (activity != null) activity.toast(host.forced ? "Encryption key not ready yet" : "Encryption key missing");
            return null;
        }
        if (host.channelId == null) {
            if (activity != null) activity.toast("Can't encrypt: unknown channel");
            return null;
        }
        String sender = activity != null ? activity.selfId() : null;
        if (sender == null) {
            if (activity != null) activity.toast("Not ready yet, try again in a moment");
            return null;
        }
        Crypto.Preset p = host.preset();
        int max = Crypto.maxPlaintextBytes(p);
        if (Crypto.utf8Length(plain) > max) {
            if (activity != null) activity.toast("Too long for " + p.label + " (max " + max + " bytes)");
            return null;
        }
        String out = Crypto.encrypt(plain, key, p, host.channelId, sender);
        if (out == null && activity != null) activity.toast("Encryption failed");
        return out;
    }

    static void decryptMessageInPlace(MessageListScreen host, Message m) {
        if (host == null || m == null) return;
        SecretKey key = host.key();
        if (key == null) return;
        String channelId = host.channelId;
        if (Crypto.looksEncrypted(m.content)) {
            String plain = Crypto.decrypt(m.content, key, channelId, m.author != null ? m.author.id : null);
            if (plain != null) {
                m.content = plain;
                extractAndCleanDimensions(m);
            }
        }
        if (Crypto.looksEncrypted(m.replyToContent)) {
            String replyChannel = m.replyToChannelId != null ? m.replyToChannelId : channelId;
            String plain = Crypto.decrypt(m.replyToContent, key, replyChannel, m.replyToAuthorId);
            if (plain != null) {
                m.replyToContent = plain;
                cleanReplyDimensions(m);
            }
        } else if (m.replyToContent != null) {
            cleanReplyDimensions(m);
        }
    }

    static void cleanReplyDimensions(Message m) {
        if (m == null || m.replyToContent == null) return;
        Matcher matcher = SIZE_TAG_PATTERN.matcher(m.replyToContent);
        if (matcher.find()) {
            try { m.replyToContent = m.replyToContent.substring(matcher.end()); }
            catch (Exception ignored) {}
        }
    }

    static void extractAndCleanDimensions(Message m) {
        if (m == null || m.content == null) return;
        Matcher matcher = SIZE_TAG_PATTERN.matcher(m.content);
        if (matcher.find()) {
            try {
                int w = Integer.parseInt(matcher.group(1));
                int h = Integer.parseInt(matcher.group(2));
                if (m.id != null) MessageMediaViewFactory.messageDimensionCache.put(m.id, new int[]{w, h});
                m.content = m.content.substring(matcher.end());
            } catch (Exception ignored) {}
        }
    }
}