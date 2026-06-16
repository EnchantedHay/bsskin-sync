package top.chancelethay.bsskinsync.mineskin;

public class MineskinResponse {

    private Data data;

    public String getTextureValue() {
        return (data != null && data.texture != null) ? data.texture.value : null;
    }

    public String getTextureSignature() {
        return (data != null && data.texture != null) ? data.texture.signature : null;
    }

    public boolean isValid() {
        String value = getTextureValue();
        String sig = getTextureSignature();
        return value != null && !value.isEmpty() && sig != null && !sig.isEmpty();
    }

    static class Data {
        Texture texture;
    }

    static class Texture {
        String value;
        String signature;
    }
}
