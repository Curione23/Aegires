package a.b.model;

import java.util.ArrayList;
import java.util.List;

public class Embed {
  public String title;
  public String description;
  public int color;
  public String type;

  public String thumbnailUrl;
  public String thumbnailProxyUrl;
  public int thumbnailWidth;
  public int thumbnailHeight;

  public String imageUrl;
  public String imageProxyUrl;
  public int imageWidth;
  public int imageHeight;

  public String videoUrl;
  public String videoProxyUrl;
  public int videoWidth;
  public int videoHeight;

  public List<EmbedField> fields = new ArrayList<EmbedField>();
  public String footer;

  public Embed() {}
}