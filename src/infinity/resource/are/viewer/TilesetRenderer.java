// Near Infinity - An Infinity Engine Browser and Editor
// Copyright (C) 2001 - 2005 Jon Olav Hauglid
// See LICENSE.txt for license information

package infinity.resource.are.viewer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

import infinity.datatype.Bitmap;
import infinity.datatype.DecNumber;
import infinity.datatype.Flag;
import infinity.datatype.ResourceRef;
import infinity.datatype.SectionCount;
import infinity.datatype.TextString;
import infinity.gui.RenderCanvas;
import infinity.resource.ResourceFactory;
import infinity.resource.graphics.ColorConvert;
import infinity.resource.graphics.TisDecoder;
import infinity.resource.key.ResourceEntry;
import infinity.resource.wed.Door;
import infinity.resource.wed.Overlay;
import infinity.resource.wed.Tilemap;
import infinity.resource.wed.WedResource;

/**
 * Specialized renderer for drawing tileset-based graphics data.
 * @author argent77
 */
public class TilesetRenderer extends RenderCanvas
{
  // Lighting conditions that can be used to draw the map (for day maps only)
  public static final int LIGHTING_DAY = 0;
  public static final int LIGHTING_TWILIGHT = 1;
  public static final int LIGHTING_NIGHT = 2;
  public static final String[] LabelVisualStates = new String[]{"Day", "Twilight", "Night"};

  // Rendering modes for tiles (affects how to render overlayed tiles)
  public static final int MODE_AUTO = 0;    // mode based on current game id
  public static final int MODE_BG1 = 1;     // forces BG1 rendering mode
  public static final int MODE_BG2 = 2;     // forces BG2 rendering mode

  private static final int MaxOverlays = 8;   // max. supported overlay entries
  private static final double MinZoomFactor = 1.0/64.0;   // lower zoom factor limit
  private static final double MaxZoomFactor = 16.0;       // upper zoom factor limit

  // Lighting adjustment for day/twilight/night times (multiplied by 10.24 for faster calculations)
  // Formula:
  // red   = (red   * LightingAdjustment[lighting][0]) >>> LightingAdjustmentShift;
  // green = (green * LightingAdjustment[lighting][1]) >>> LightingAdjustmentShift;
  // blue  = (blue  * LightingAdjustment[lighting][2]) >>> LightingAdjustmentShift;
  private static final int[][] LightingAdjustment = new int[][]
      // (100%, 100%, 100%),   (100%, 85%, 80%),      (45%, 45%, 85%)
      { {0x400, 0x400, 0x400}, {0x400, 0x366, 0x333}, {0x1cd, 0x1cd, 0x366} };
  private static final int LightingAdjustmentShift = 10;   // use in place of division

  // keeps track of registered listener objects
  private final List<TilesetChangeListener> listChangeListener = new ArrayList<TilesetChangeListener>();
  // graphics data for all tiles of each overlay
  private final List<Tileset> listTilesets = new ArrayList<Tileset>(MaxOverlays);
  // array of tile indices used for closed door states for each door structure
  private final List<DoorInfo> listDoorTileIndices = new ArrayList<DoorInfo>();

  private final BufferedImage workingTile = ColorConvert.createCompatibleImage(64, 64, true); // internally used for drawing tile graphics
  private WedResource wed;                // current wed resource
  private int renderingMode = MODE_AUTO;  // the rendering mode to use for processing overlayed tiles
  private boolean overlaysEnabled = true; // indicates whether to draw overlays
  private boolean blendedOverlays;        // indicates whether to blend overlays with tile graphics
  private boolean hasChangedMap, hasChangedAppearance, hasChangedOverlays, hasChangedDoorState;
  private boolean isClosed = false;       // opened/closed state of door tiles
  private boolean showGrid = false;       // indicates whether to draw a grid on the tiles
  private boolean forcedInterpolation = false;  // indicates whether to use a pre-defined interpolation type or set one based on zoom factor
  private double zoomFactor = 1.0;        // zoom factor for drawing the map
  private int lighting = LIGHTING_DAY;    // the lighting condition to be used (day/twilight/night)

  /**
   * Returns the number of supported lighting modes.
   */
  public static int getLightingModesCount()
  {
    return LabelVisualStates.length;
  }

  public TilesetRenderer()
  {
    this(null);
  }

  public TilesetRenderer(WedResource wed)
  {
    super();
    init(wed);
  }

  /**
   * Adds a ChangeListener to the component. A change event will be triggered on changing map dimensions
   * or setting up a new map.
   * @param listener The listener to add.
   */
  public void addChangeListener(TilesetChangeListener listener)
  {
    if (listener != null) {
      if (listChangeListener.indexOf(listener) < 0) {
        listChangeListener.add(listener);
      }
    }
  }

  /**
   * Returns an array of all the ChangeListeners added to this component.
   * @return All ChangeListeners added or an empty array.
   */
  public TilesetChangeListener[] getChangeListeners()
  {
    return (TilesetChangeListener[])listChangeListener.toArray();
  }

  /**
   * Removes a ChangeListener from the component.
   * @param listener The listener to remove.
   */
  public void removeChangeListener(TilesetChangeListener listener)
  {
    if (listener != null) {
      listChangeListener.remove(listener);
    }
  }

  /**
   * Initializes and displays the specified map. The current map will be discarded.
   * Triggers a change event.
   * @param wed WED resource structure used to construct a map.
   * @return true if map has been initialized successfully, false otherwise.
   */
  public boolean loadMap(WedResource wed)
  {
    if (this.wed != wed) {
      if (init(wed)) {
        return true;
      } else {
        return false;
      }
    } else {
      return true;
    }
  }

  /**
   * Returns whether a map has been loaded.
   */
  public boolean isMapLoaded()
  {
    return isInitialized();
  }

  /**
   * Returns the currently loaded WED resources.
   */
  public WedResource getWed()
  {
    return wed;
  }

  /**
   * Removes the current map and all associated data from memory.
   */
  public void clear()
  {
    release(true);
  }

  /**
   * Returns the current mode for processing overlays.
   */
  public int getRenderingMode()
  {
    return renderingMode;
  }

  /**
   * Specify how to draw overlayed tiles. Possible choices are MODE_AUTO, MODE_BG1 and MODE_BG2.
   * @param mode The new rendering mode
   */
  public void setRenderingMode(int mode)
  {
    if (mode < MODE_AUTO) mode = MODE_AUTO; else if (mode > MODE_BG2) mode = MODE_BG2;
    if (mode != renderingMode) {
      renderingMode = mode;
      hasChangedOverlays = true;
      updateDisplay();
    }
  }

  /**
   * Returns whether overlays are drawn.
   */
  public boolean isOverlaysEnabled()
  {
    return overlaysEnabled;
  }

  /**
   * Enable or disable the display of overlays.
   */
  public void setOverlaysEnabled(boolean enable)
  {
    if (overlaysEnabled != enable) {
      overlaysEnabled = enable;
      hasChangedOverlays = true;
      updateDisplay();
    }
  }

  /**
   * Returns whether the current map contains overlays
   */
  public boolean hasOverlays()
  {
    if (isInitialized()) {
      return !listTilesets.get(0).listOverlayTiles.isEmpty();
    }
    return false;
  }

  /**
   * Returns the current zoom factor.
   * @return The currently used zoom factor.
   */
  public double getZoomFactor()
  {
    return zoomFactor;
  }

  /**
   * Sets a new zoom factor for display. Clamped to range [0.25, 4.0].
   * Triggers a change event if the zoom factor changes.
   * @param factor The new zoom factor to use.
   */
  public void setZoomFactor(double factor)
  {
    if (factor < MinZoomFactor) factor = MinZoomFactor; else if (factor > MaxZoomFactor) factor = MaxZoomFactor;
    if (factor != zoomFactor) {
      zoomFactor = factor;
      hasChangedMap = true;
      updateDisplay();
    }
  }

  /**
   * Returns whether the renderer is forced to use the predefined interpolation type on scaling.
   */
  public boolean isForcedInterpolation()
  {
    return forcedInterpolation;
  }

  /**
   * Specifies whether the renderer uses the best interpolation type based on the current zoom factor
   * or uses a predefined interpolation type only.
   * @param set If <code>true</code>, uses a predefined interpolation type only.
   *            If <code>false</code>, chooses an interpolation type automatically.
   */
  public void setForcedInterpolation(boolean set)
  {
    if (set != forcedInterpolation) {
      forcedInterpolation = set;
      hasChangedAppearance = true;
      updateDisplay();
    }
  }

  /**
   * Returns the opened/closed state of door tiles.
   * @return The opened/closed state of door tiles.
   */
  public boolean isDoorsClosed()
  {
    return isClosed;
  }

  /**
   * Sets the opened/closed state of door tiles. Triggers a change event if the state changes.
   * @param isClosed The new opened/closed state of door tiles.
   */
  public void setDoorsClosed(boolean isClosed)
  {
    if (this.isClosed != isClosed) {
      this.isClosed = isClosed;
      hasChangedDoorState = true;
      updateDisplay();
    }
  }

  /**
   * Returns whether the current map contains closeable doors.
   */
  public boolean hasDoors()
  {
    if (isInitialized()) {
      return !listDoorTileIndices.isEmpty();
    }
    return false;
  }

  /**
   * Returns the currently used lighting condition.
   * @return The currently used lighting condition.
   */
  public int getLighting()
  {
    return lighting;
  }

  /**
   * Sets a new lighting condition to be used to draw the map. Only meaningful for day maps.
   * @param lighting The lighting condition to use. (One of the constants <code>LIGHTING_DAY</code>,
   *                 <code>LIGHTING_DUSK</code> or <code>LIGHTING_NIGHT</code>)
   */
  public void setLighting(int lighting)
  {
    if (lighting < LIGHTING_DAY) lighting = LIGHTING_DAY;
    else if (lighting > LIGHTING_NIGHT) lighting = LIGHTING_NIGHT;

    if (lighting != this.lighting) {
      this.lighting = lighting;
      hasChangedAppearance = true;
      updateDisplay();
    }
  }

  public boolean isGridEnabled()
  {
    return showGrid;
  }

  public void setGridEnabled(boolean enable)
  {
    if (enable != showGrid) {
      showGrid = enable;
      hasChangedAppearance = true;
      updateDisplay();
    }
  }

  /**
   * Returns the width of the current map in pixels. Zoom factor is not taken into account.
   * @return Map width in pixels.
   */
  public int getMapWidth(boolean scaled)
  {
    if (isInitialized()) {
      int w = listTilesets.get(0).tilesX * 64;
      if (scaled) {
        return (int)Math.ceil((double)w * zoomFactor);
      } else {
        return w;
      }
    } else {
      return 0;
    }
  }

  /**
   * Returns the height of the current map in pixels. Zoom factor is not taken into account.
   * @return Map height in pixels.
   */
  public int getMapHeight(boolean scaled)
  {
    if (isInitialized()) {
      int h = listTilesets.get(0).tilesY * 64;
      if (scaled) {
        return (int)Math.ceil((double)h * zoomFactor);
      } else {
        return h;
      }
    } else {
      return 0;
    }
  }

  /**
   * Advances the frame index by one for animated overlays.
   */
  public void advanceTileFrame()
  {
    for (int i = 1; i < listTilesets.size(); i++) {
      listTilesets.get(i).advanceTileFrame();
      hasChangedOverlays = true;
    }
    if (hasChangedOverlays) {
      updateDisplay();
    }
  }

  /**
   * Sets the frame index for animated overlay tiles.
   * @param index The frame index to set.
   */
  public void setTileFrame(int index)
  {
    for (int i = 1; i < listTilesets.size(); i++) {
      listTilesets.get(i).setTileFrame(index);
      hasChangedOverlays = true;
    }
    if (hasChangedOverlays) {
      updateDisplay();
    }
  }

  protected void updateSize()
  {
    if (isInitialized()) {
      int w = getMapWidth(true);
      int h = getMapHeight(true);
      Dimension newDim = new Dimension(w, h);
      invalidate();
      setScalingEnabled(zoomFactor != 1.0);
      if (!forcedInterpolation) {
        setInterpolationType((zoomFactor < 1.0) ? TYPE_BILINEAR : TYPE_NEAREST_NEIGHBOR);
      }
      setSize(newDim);
      setPreferredSize(newDim);
      setMinimumSize(newDim);
      validate();
      repaint();
    } else {
      super.updateSize();
    }
  }

  @Override
  protected void paintCanvas(Graphics g)
  {
    super.paintCanvas(g);
    if (showGrid) {
      double tileWidth = 64.0 * zoomFactor;
      double tileHeight = 64.0 * zoomFactor;
      double mapWidth = (double)getMapWidth(true);
      double mapHeight = (double)getMapHeight(true);
      g.setColor(Color.GRAY);
      for (double curY = 0.0; curY < mapHeight; curY += tileHeight) {
        for (double curX = 0.0; curX < mapWidth; curX += tileWidth) {
          g.drawLine((int)Math.ceil(curX), (int)Math.ceil(curY + tileHeight),
                     (int)Math.ceil(curX + tileWidth), (int)Math.ceil(curY + tileHeight));
          g.drawLine((int)Math.ceil(curX + tileWidth), (int)Math.ceil(curY),
                     (int)Math.ceil(curX + tileWidth), (int)Math.ceil(curY + tileHeight));
        }
      }
    }
  }

  // Resizes the current image or creates a new one if needed
  private boolean updateImageSize()
  {
    if (isInitialized()) {
      BufferedImage curImg = (BufferedImage)getImage();
      if (curImg == null || curImg.getWidth() != getMapWidth(false) ||
          curImg.getHeight() != getMapHeight(false)) {
        if (curImg != null) {
          curImg = null;
        }
        BufferedImage newImg = ColorConvert.createCompatibleImage(getMapWidth(false), getMapHeight(false), true);
        if (newImg == null) {
          return false;
        }
        setImage(newImg);
      }
      updateSize();
      return true;
    }
    return false;
  }

  // Initializes a new map
  private boolean init(WedResource wed)
  {
    release(false);

    // resetting states
    blendedOverlays = (ResourceFactory.getGameID() == ResourceFactory.ID_BG2 ||
                       ResourceFactory.getGameID() == ResourceFactory.ID_BG2TOB ||
                       ResourceFactory.getGameID() == ResourceFactory.ID_BG2EE);
    lighting = LIGHTING_DAY;

    // loading map data
    if (wed != null) {
      if (initWed(wed)) {
        this.wed = wed;
        if (!updateImageSize()) {
          return false;
        }
      } else {
        return false;
      }
    }
    hasChangedMap = true;

    // drawing map data
    updateDisplay();

    return true;
  }

  // Removes all map-related data from memory
  private void release(boolean forceUpdate)
  {
    if (isInitialized()) {
      wed = null;
      listTilesets.clear();
      listDoorTileIndices.clear();

      Image img = getImage();
      if (img != null) {
        if (forceUpdate) {
          Graphics2D g = (Graphics2D)img.getGraphics();
          g.setBackground(new Color(0, true));
          g.clearRect(0, 0, img.getWidth(null), img.getHeight(null));
          g.dispose();
          repaint();
        }
      }

      hasChangedMap = false;
      hasChangedAppearance = false;
      hasChangedOverlays = false;
      hasChangedDoorState = false;
    }
  }

  // Simply returns whether a map has been loaded
  private boolean isInitialized()
  {
    return (wed != null) && (!listTilesets.isEmpty());
  }

  private boolean initWed(WedResource wed)
  {
    if (wed != null) {
      // loading overlay structures
      SectionCount sc = (SectionCount)wed.getAttribute("# overlays");
      if (sc != null) {
        for (int i = 0; i < sc.getValue(); i++) {
          Overlay ovl = (Overlay)wed.getAttribute(String.format("Overlay %1$d", i));
          if (ovl != null) {
            listTilesets.add(new Tileset(wed, ovl));
          } else {
            release(true);
            return false;
          }
        }
      } else {
        release(true);
        return false;
      }

      // loading door structures
      sc = (SectionCount)wed.getAttribute("# doors");
      if (sc != null) {
        for (int i = 0; i < sc.getValue(); i++) {
          Door door = (Door)wed.getAttribute(String.format("Door %1$d", i));
          if (door != null) {
            String name = ((TextString)door.getAttribute("Name")).toString();
            boolean isClosed = ((Bitmap)door.getAttribute("Is door?")).getValue() == 1;
            int tileCount = ((SectionCount)door.getAttribute("# tilemap indexes")).getValue();
            if (tileCount < 0) tileCount = 0;
            int[] indices = new int[tileCount];
            for (int j = 0; j < tileCount; j++) {
              indices[j] = ((DecNumber)door.getAttribute(String.format("Tilemap index %1$d", j))).getValue();
            }
            listDoorTileIndices.add(new DoorInfo(name, isClosed, indices));
          } else {
            listDoorTileIndices.add(new DoorInfo("", true, new int[]{}));   // needed as placeholder
          }
        }
      } else {
        release(true);
        return false;
      }

      return true;
    } else {
      return false;
    }
  }

  // (Re-)draw display, resize if needed
  private void updateDisplay()
  {
    if (isInitialized()) {
      updateImageSize();
      if (hasChangedMap || hasChangedAppearance) {
        // redraw each tile
        drawAllTiles();
      } else {
        if (hasChangedOverlays) {
          // redraw overlayed tiles only
          drawOverlayTiles();
        }
        if (hasChangedDoorState) {
          // redraw door tiles only
          drawDoorTiles();
        }
      }
      repaint();
      notifyChangeListeners();
      hasChangedMap = false;
      hasChangedAppearance = false;
      hasChangedOverlays = false;
      hasChangedDoorState = false;
    }
  }

  // draws all tiles of the map
  private void drawAllTiles()
  {
    for (int i = 0; i < listTilesets.get(0).listTiles.size(); i++) {
      Tile tile = listTilesets.get(0).listTiles.get(i);
      drawTile(tile, isDoorTile(tile));
    }
  }

  // draws overlayed tiles only
  private void drawOverlayTiles()
  {
    for (int i = 0; i < listTilesets.get(0).listOverlayTiles.size(); i++) {
      Tile tile = listTilesets.get(0).listOverlayTiles.get(i);
      drawTile(tile, isDoorTile(tile));
    }
  }

  // draws door tiles only
  private void drawDoorTiles()
  {
    for (int i = 0; i < listDoorTileIndices.size(); i++) {
      DoorInfo di = listDoorTileIndices.get(i);
      for (int j = 0; j < di.getIndicesCount(); j++) {
        Tile tile = listTilesets.get(0).listTiles.get(di.getIndex(j));
        drawTile(tile, isDoorTile(tile));
      }
    }
  }

  // draws the specified tile into the target graphics buffer
  private synchronized void drawTile(Tile tile, boolean isDoorTile)
  {
    if (tile != null) {
      boolean isDoorClosed = (ResourceFactory.getGameID() == ResourceFactory.ID_TORMENT) ? !isClosed : isClosed;
      int[] target = ((DataBufferInt)workingTile.getRaster().getDataBuffer()).getData();

      int fa = 255, fr = 0, fg = 0, fb = 0;
      if (overlaysEnabled && tile.hasOverlay()) {   // overlayed tile
        // preparing graphics data
        int overlay = tile.getOverlayIndex();
        if (overlay < listTilesets.size() && !listTilesets.get(overlay).listTiles.isEmpty()) {
          int tileIdx = listTilesets.get(overlay).listTiles.get(0).getPrimaryIndex();
          BufferedImage imgOvl = null;
          int[] srcOvl = null;
          if (tileIdx >= 0) {
            imgOvl = listTilesets.get(overlay).listTileData.get(tileIdx);
            srcOvl = ((DataBufferInt)imgOvl.getRaster().getDataBuffer()).getData();
          }
          BufferedImage imgPri = null;
          int[] srcPri = null;
          tileIdx = tile.getPrimaryIndex();
          if (tileIdx >= 0) {
            imgPri = listTilesets.get(0).listTileData.get(tileIdx);
            srcPri = ((DataBufferInt)imgPri.getRaster().getDataBuffer()).getData();
          }
          BufferedImage imgSec = null;
          int[] srcSec = null;
          tileIdx = tile.getSecondaryIndex();
          if (tileIdx >= 0) {
            imgSec = listTilesets.get(0).listTileData.get(tileIdx);
            srcSec = ((DataBufferInt)imgSec.getRaster().getDataBuffer()).getData();
          }

          // determining correct rendering mode
          boolean blended = (renderingMode == MODE_AUTO && blendedOverlays) || (renderingMode == MODE_BG2);

          // drawing tile graphics
          boolean pa, sa;
          int pr, pg, pb, sr, sg, sb, or, og, ob;
          for (int ofs = 0; ofs < 4096; ofs++) {
            if (blended) {    // BG2/BGEE mode overlays
              // extracting color components
              if (srcPri != null) {
                pa = (srcPri[ofs] & 0xff000000) != 0;
                pr = (srcPri[ofs] >>> 16) & 0xff;
                pg = (srcPri[ofs] >>> 8) & 0xff;
                pb = srcPri[ofs] & 0xff;
              } else {
                pa = false;
                pr = pg = pb = 0;
              }
              if (srcSec != null) {
                sa = (srcSec[ofs] & 0xff000000) != 0;
                sr = (srcSec[ofs] >>> 16) & 0xff;
                sg = (srcSec[ofs] >>> 8) & 0xff;
                sb = srcSec[ofs] & 0xff;
              } else {
                sa = false;
                sr = sg = sb = 0;
              }
              if (srcOvl != null) {
                or = (srcOvl[ofs] >>> 16) & 0xff;
                og = (srcOvl[ofs] >>> 8) & 0xff;
                ob = srcOvl[ofs] & 0xff;
              } else {
                or = og = ob = 0;
              }

              // blending modes depend on transparency states of primary and secondary pixels
              if (pa && !sa) {
                if (tile.isTisV1()) {
                  fr = (pr + or) >>> 1;
                  fg = (pg + og) >>> 1;
                  fb = (pb + ob) >>> 1;
                } else {
                  if (srcSec != null) {
                    fr = pr;
                    fg = pg;
                    fb = pb;
                  } else {
                    fr = (pr + or) >>> 1;
                    fg = (pg + og) >>> 1;
                    fb = (pb + ob) >>> 1;
                  }
                }
              } else if (pa && sa) {
                fr = (pr + sr) >>> 1;
                fg = (pg + sg) >>> 1;
                fb = (pb + sb) >>> 1;
              } else if (!pa && !sa) {
                fr = or;
                fg = og;
                fb = ob;
              } else if (!pa && sa) {
                fr = (sr + or) >>> 1;
                fg = (sg + og) >>> 1;
                fb = (sb + ob) >>> 1;
              }
            } else {    // BG1 mode overlays
              int[] src = (isDoorTile && isDoorClosed) ? srcSec : srcPri;
              if (src != null) {
                if ((src[ofs] & 0xff000000) != 0 && src != null) {
                  fr = (src[ofs] >>> 16) & 0xff;
                  fg = (src[ofs] >>> 8) & 0xff;
                  fb = src[ofs] & 0xff;
                } else if (srcOvl != null) {
                  fr = (srcOvl[ofs] >>> 16) & 0xff;
                  fg = (srcOvl[ofs] >>> 8) & 0xff;
                  fb = srcOvl[ofs] & 0xff;
                }
              } else {
                fa = fr = fg = fb = 0;
              }
            }

            // applying lighting conditions
            fr = (fr * LightingAdjustment[lighting][0]) >>> LightingAdjustmentShift;
            fg = (fg * LightingAdjustment[lighting][1]) >>> LightingAdjustmentShift;
            fb = (fb * LightingAdjustment[lighting][2]) >>> LightingAdjustmentShift;
            target[ofs] = (fa << 24) | (fr << 16) | (fg << 8) | fb;
          }
          if (imgOvl != null) {
            srcOvl = null;
            imgOvl.flush();
            imgOvl = null;
          }
          if (imgPri != null) {
            srcPri = null;
            imgPri.flush();
            imgPri = null;
          }
          if (imgSec != null) {
            srcSec = null;
            imgSec.flush();
            imgSec = null;
          }
        }
      } else {    // no overlay or disabled overlay
        // preparing tile graphics
        BufferedImage imgTile = null;
        int[] srcTile = null;
        int tileIdx = (!isDoorClosed || !isDoorTile) ? tile.getPrimaryIndex() : tile.getSecondaryIndex();
        if (tileIdx >= 0) {
          imgTile = listTilesets.get(0).listTileData.get(tileIdx);
          srcTile = ((DataBufferInt)imgTile.getRaster().getDataBuffer()).getData();
        }

        // drawing tile graphics
        if (srcTile != null) {
          for (int ofs = 0; ofs < 4096; ofs++) {
            fr = (srcTile[ofs] >>> 16) & 0xff;
            fg = (srcTile[ofs] >>> 8) & 0xff;
            fb = srcTile[ofs] & 0xff;
            fr = (fr * LightingAdjustment[lighting][0]) >>> LightingAdjustmentShift;
            fg = (fg * LightingAdjustment[lighting][1]) >>> LightingAdjustmentShift;
            fb = (fb * LightingAdjustment[lighting][2]) >>> LightingAdjustmentShift;
            target[ofs] = 0xff000000 | (fr << 16) | (fg << 8) | fb;
          }
        } else {
          // no tile = transparent pixel data (work-around for faulty tiles in BG1's WEDs)
          for (int ofs = 0; ofs < 4096; ofs++) {
            target[ofs] = 0;
          }
        }

        if (imgTile != null) {
          srcTile = null;
          imgTile.flush();
          imgTile = null;
        }
      }

      // drawing tile on canvas
      Graphics2D g = (Graphics2D)getImage().getGraphics();
      g.drawImage(workingTile, tile.getX(), tile.getY(), null);
      g.dispose();
      target = null;
    }
  }

  // Returns whether the specified tile is used as a door tile
  private boolean isDoorTile(Tile tile)
  {
    if (tile != null) {
      int tileIdx = tile.getPrimaryIndex();
      for (int i = 0; i < listDoorTileIndices.size(); i++) {
        DoorInfo di = listDoorTileIndices.get(i);
        for (int j = 0; j < di.getIndicesCount(); j++) {
          if (di.getIndex(j) == tileIdx) {
            return true;
          }
        }
      }
    }
    return false;
  }

  // Notify all registered change listeners
  private void notifyChangeListeners()
  {
    if (hasChangedMap || hasChangedAppearance || hasChangedOverlays || hasChangedDoorState) {
      for (int i = 0; i < listChangeListener.size(); i++) {
        listChangeListener.get(i).tilesetChanged(new TilesetChangeEvent(this,
            hasChangedMap, hasChangedAppearance, hasChangedOverlays, hasChangedDoorState));
      }
    }
  }


//----------------------------- INNER CLASSES -----------------------------

  // Stores data of a specific overlay structure
  private static class Tileset
  {
    // graphics data for all tiles of this overlay
    public final List<BufferedImage> listTileData = new ArrayList<BufferedImage>();
    // info structures for all tiles of this overlay
    public final List<Tile> listTiles = new ArrayList<Tile>();
    // lists references to all tiles containing overlays from listTiles
    public final List<Tile> listOverlayTiles = new ArrayList<Tile>();

    public int tilesX, tilesY;    // stores number of tiles per row/column

    public Tileset(WedResource wed, Overlay ovl)
    {
      init(wed, ovl);
    }

    public void advanceTileFrame()
    {
      for (int i = 0; i < listTiles.size(); i++) {
        listTiles.get(i).advancePrimaryIndex();
      }
    }

    public void setTileFrame(int index)
    {
      for (int i = 0; i < listTiles.size(); i++) {
        listTiles.get(i).setCurrentPrimaryIndex(index);
      }
    }

    private void init(WedResource wed, Overlay ovl)
    {
      if (wed != null && ovl != null) {
        // storing tile data
        boolean isTilesetV1 = true;
        ResourceEntry tisEntry = getTisResource(wed, ovl);
        if (tisEntry != null) {
          try {
            TisDecoder decoder = new TisDecoder(tisEntry);
            isTilesetV1 = decoder.info().type() == TisDecoder.TisInfo.TisType.PALETTE;
            for (int i = 0; i < decoder.info().tileCount(); i++) {
              listTileData.add(decoder.decodeTile(i));
            }
          } catch (Exception e) {
            e.printStackTrace();
            return;
          }
        }

        // storing tile information
        tilesX = ((DecNumber)ovl.getAttribute("Width")).getValue();
        tilesY = ((DecNumber)ovl.getAttribute("Height")).getValue();
        int tileCount = tilesX * tilesY;
        for (int i = 0; i < tileCount; i++) {
          Tilemap tile = (Tilemap)ovl.getAttribute(String.format("Tilemap %1$d", i));
          // tile coordinates in pixels
          int x = (i % tilesX) * 64;
          int y = (i / tilesX) * 64;

          if (tile != null) {
            // initializing list of primary tile indices
            int index = ((DecNumber)tile.getAttribute("Primary tile index")).getValue();
            int count = ((DecNumber)tile.getAttribute("Primary tile count")).getValue();
            if (count < 0) count = 0;
            int[] tileIdx = new int[count];
            for (int j = 0; j < count; j++) {
              if (index >= 0) {
                try {
                  tileIdx[j] = ((DecNumber)ovl.getAttribute(String.format("Tilemap index %1$d", index + j))).getValue();
                } catch (Exception e) {
                  tileIdx[j] = -1;
                  e.printStackTrace();
                }
              } else {
                tileIdx[j] = -1;
              }
            }

            // initializing secondary tile index
            int tileIdx2 = ((DecNumber)tile.getAttribute("Secondary tile index")).getValue();

            // initializing overlay flags
            int flags = 0;
            Flag drawOverlays = (Flag)tile.getAttribute("Draw Overlays");
            for (int j = 0; j < 8; j++) {
              if (drawOverlays.isFlagSet(j)) {
                flags |= 1 << j;
              }
            }

            listTiles.add(new Tile(x, y, count, tileIdx, tileIdx2, flags, isTilesetV1));
          } else {
            listTiles.add(new Tile(x, y, 0, new int[]{}, -1, 0, true));     // needed as placeholder
          }
        }

        // grouping overlayed tiles for faster access
        for (int i = 0; i < listTiles.size(); i++) {
          Tile tile = listTiles.get(i);
          if (tile.getFlags() > 0) {
            listOverlayTiles.add(tile);
          }
        }

      } else {
        tilesX = tilesY = 0;
      }
    }

    // Returns the TIS file defined in the specified Overlay structure
    private ResourceEntry getTisResource(WedResource wed, Overlay ovl)
    {
      ResourceEntry entry = null;
      if (wed != null && ovl != null) {
        String tisName = ((ResourceRef)ovl.getAttribute("Tileset")).getResourceName().toUpperCase();
        if (tisName == null || "None".equalsIgnoreCase(tisName)) {
          tisName = "";
        }
        if (!tisName.isEmpty()) {
          // Special: BG1 has a weird way to select extended night tilesets
          if (ResourceFactory.getGameID() == ResourceFactory.ID_BG1 ||
              ResourceFactory.getGameID() == ResourceFactory.ID_BG1TOTSC) {
            String wedName = wed.getResourceEntry().getResourceName().toUpperCase();
            if (wedName.lastIndexOf('.') > 0) {
              wedName = wedName.substring(0, wedName.lastIndexOf('.'));
            }
            if (tisName.lastIndexOf('.') > 0) {
              tisName = tisName.substring(0, tisName.lastIndexOf('.'));
            }

            // XXX: not sure whether this check is correct
            if (wedName.length() > 6 && wedName.charAt(6) == 'N' && tisName.length() == 6) {
//            if (wedName.equalsIgnoreCase(tisName + "N")) {
              entry = ResourceFactory.getInstance().getResourceEntry(tisName + "N.TIS");
            }
            if (entry == null) {
              entry = ResourceFactory.getInstance().getResourceEntry(tisName + ".TIS");
            }
          } else {
            entry = ResourceFactory.getInstance().getResourceEntry(tisName);
          }
        }
      }
      return entry;
    }

  }


  // Stores tilemap information only (no graphics data)
  private static class Tile
  {
    private int tileIdx2;     // (start) indices of primary and secondary tiles
    private int[] tileIdx;    // tile indices for primary and secondary tiles
    private int tileCount, curTile;   // number of primary tiles, currently selected tile
    private int x, y, flags;          // (x, y) as pixel coordinates, flags defines overlay usage
    private boolean isTisV1;

    public Tile(int x, int y, int tileCount, int[] index, int index2, int flags, boolean isTisV1)
    {
      if (tileCount < 0) tileCount = 0;
      this.x = x;
      this.y = y;
      this.tileCount = tileCount;
      this.curTile = 0;
      this.tileIdx = index;
      this.tileIdx2 = index2;
      this.flags = flags;
      this.isTisV1 = isTisV1;
    }

    // Returns whether this tile references a TIS v1 resource
    public boolean isTisV1()
    {
      return isTisV1;
    }

    // Returns the current primary tile index
    public int getPrimaryIndex()
    {
      if (tileIdx.length > 0) {
        return tileIdx[curTile];
      } else {
        return -1;
      }
    }

    // Returns the primary tile index of the specified frame (useful for animated tiles)
    public int getPrimaryIndex(int frame)
    {
      if (tileCount > 0) {
        return tileIdx[frame % tileCount];
      } else {
        return -1;
      }
    }

    // Sets a new selected primary tile index
    public void setCurrentPrimaryIndex(int frame)
    {
      if (tileCount > 0) {
        if (frame < 0) frame = 0; else if (frame >= tileCount) frame = tileCount - 1;
        curTile = frame;
      }
    }

    // Returns the primary tile count
    public int getPrimaryIndexCount()
    {
      return tileCount;
    }

    // Advances the primary tile index by 1 for animated tiles, wraps around automatically
    public void advancePrimaryIndex()
    {
      if (tileCount > 0) {
        curTile = (curTile + 1) % tileCount;
      }
    }

    // Returns the secondary tile index (or -1 if not available)
    public int getSecondaryIndex()
    {
      return tileIdx2;
    }

    // Returns the x pixel coordinate of this tile
    public int getX()
    {
      return x;
    }

    // Returns y pixel coordinate of this tile
    public int getY()
    {
      return y;
    }

    // Returns the unprocessed flags data
    public int getFlags()
    {
      return flags;
    }

    // Returns true if overlays have been defined
    public boolean hasOverlay()
    {
      return flags != 0;
    }

    // Returns the overlay index (or 0 otherwise)
    public int getOverlayIndex()
    {
      if (flags != 0) {
        for (int i = 1; i < 8; i++) {
          if ((flags & (1 << i)) != 0) {
            return i;
          }
        }
      }
      return 0;
    }
  }


  // Stores relevant information about door structures
  private static class DoorInfo
  {
    private String name;        // door info structure name
    private boolean isClosed;   // indicates the door state for the specified list of tile indices
    private int[] indices;      // list of tilemap indices used for the door

    public DoorInfo(String name, boolean isClosed, int[] indices)
    {
      this.name = (name != null) ? name : "";
      this.isClosed = isClosed;
      this.indices = (indices != null) ? indices : new int[0];
    }

    // Returns the name of the door structure
    public String getName()
    {
      return name;
    }

    // Returns whether the tile indices are used for the closed state of the door
    public boolean isClosed()
    {
      return isClosed;
    }

    // Returns number of tiles used in this door structure
    public int getIndicesCount()
    {
      return indices.length;
    }

    // Returns tilemap index of specified entry
    public int getIndex(int entry)
    {
      if (entry >= 0 && entry < indices.length) {
        return indices[entry];
      } else {
        return -1;
      }
    }
  }
}
