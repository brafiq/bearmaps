import java.util.HashMap;
import java.util.Map;

/**
 * This class provides all code necessary to take a query box and produce
 * a query result. The getMapRaster method must return a Map containing all
 * seven of the required fields, otherwise the front end code will probably
 * not draw the output correctly.
 */
public class Rasterer {
    private String[][] rendergrid;
    private Number rasterullon;
    private Number rasterullat;
    private Number rasterlrlon;
    private Number rasterlrlat;
    private Integer depth;
    private Integer firstHorizTileIndex;
    private Integer firstVertTileIndex;
    private Double latWidth;
    private Double lonWidth;
    private Double[] lonDPPArray;
    private Double[] lrlonArray;
    private Double[] lrlatArray;

    public Rasterer() {
        //array that stores the LonDPP of a tile specific to each depth level
        lonDPPArray = new Double[8];

        //array that stores the bottom right longitude of the left-most tile at each depth
        lrlonArray = new Double[8];

        //array that stores the bottom right latitude of the right-most tile at each depth
        lrlatArray = new Double[8];
    }

    /**
     * Takes a user query and finds the grid of images that best matches the query. These
     * images will be combined into one big image (rastered) by the front end. <br>
     *
     *     The grid of images must obey the following properties, where image in the
     *     grid is referred to as a "tile".
     *     <ul>
     *         <li>The tiles collected must cover the most longitudinal distance per pixel
     *         (LonDPP) possible, while still covering less than or equal to the amount of
     *         longitudinal distance per pixel in the query box for the user viewport size. </li>
     *         <li>Contains all tiles that intersect the query bounding box that fulfill the
     *         above condition.</li>
     *         <li>The tiles must be arranged in-order to reconstruct the full image.</li>
     *     </ul>
     *
     * @param params Map of the HTTP GET request's query parameters - the query box and
     *               the user viewport width and height.
     *
     * @return A map of results for the front end as specified: <br>
     * "render_grid"   : String[][], the files to display. <br>
     * "raster_ul_lon" : Number, the bounding upper left longitude of the rastered image. <br>
     * "raster_ul_lat" : Number, the bounding upper left latitude of the rastered image. <br>
     * "raster_lr_lon" : Number, the bounding lower right longitude of the rastered image. <br>
     * "raster_lr_lat" : Number, the bounding lower right latitude of the rastered image. <br>
     * "depth"         : Number, the depth of the nodes of the rastered image <br>
     * "query_success" : Boolean, whether the query was able to successfully complete; don't
     *                    forget to set this to true on success! <br>
     */
    public Map<String, Object> getMapRaster(Map<String, Double> params) {
        Map<String, Object> results = new HashMap<>();
        if (params.get("lrlon") < MapServer.ROOT_ULLON
                || params.get("ullon") > MapServer.ROOT_LRLON
                || params.get("ullat") < MapServer.ROOT_LRLAT
                || params.get("lrlat") > MapServer.ROOT_ULLAT
                || params.get("ullon") > params.get("lrlon")
                || params.get("ullat") < params.get("lrlat")) {
            System.out.println(rasterullon);
            System.out.println(rasterullat);
            System.out.println(rasterlrlon);
            System.out.println(rasterlrlat);
            System.out.println(depth);
            results.put("query_success", false);
        } else {
            //calculate the user's LonDPP val
            Double userLonDPP = getUserLonDPP(params);

            //set the values of the LonDPP_Array, lrlat_Array, and
            //the lrlon_Array values at each depth
            setArrays(params);

            //find the depth that the displayed tiles should come from
            depth = findDepth(lonDPPArray, userLonDPP);

            //find the longitudinal width of the tiles to be used
            lonWidth = lrlonArray[depth] - MapServer.ROOT_ULLON;

            //find the latitudinal width of the tiles to be used
            latWidth = ((MapServer.ROOT_ULLAT - MapServer.ROOT_LRLAT) / Math.pow(2, depth));

            //find the index of the first horizontal tile
            firstHorizTileIndex = firstHorizTileIndex(params);

            //find the index of the first vertical tile
            firstVertTileIndex = firstVertTileIndex(params);

            //find the number of tiles to add in x-direction
            Integer numXTiles = numXTiles(params);

            //find the number of tiles to add in y-direction
            Integer numYTiles = numYTiles(params);


            //add those specific tiles to the render grid
            addToRenderGrid(numXTiles, numYTiles, firstHorizTileIndex, firstVertTileIndex);

            //adding calculated values into results map
            results.put("render_grid", rendergrid);
            results.put("raster_ul_lon", rasterullon);
            results.put("raster_ul_lat", rasterullat);
            results.put("raster_lr_lon", rasterlrlon);
            results.put("raster_lr_lat", rasterlrlat);
            results.put("depth", depth);
            results.put("query_success", true);
        }
        return results;
    }

    /**
     * Returns the LonDPP value based on the user's query params.
     */
    private Double getUserLonDPP(Map<String, Double> params) {
        return (params.get("lrlon")
                - params.get("ullon")) / params.get("w");
    }

    /**
     * Sets the values of the LonDPP Array, lrlon Array, and the
     *  lrlat Array.
     */
    private void setArrays(Map<String, Double> params) {
        //creating LonDPP, lrlat, and lrlon arrays
        //invariants:
        //  1. ullon of a tile always stays the same at each depth
        //  2. lrlon always changes by a factor of 1/2

        Double lrlonVal;
        Double lrlatVal;
        Double lonDPPVal = (MapServer.ROOT_LRLON - MapServer.ROOT_ULLON) / MapServer.TILE_SIZE;
        for (int i = 0; i < lonDPPArray.length; i++) {
            lonDPPArray[i] = lonDPPVal;
            lonDPPVal = lonDPPVal / 2;
            lrlonVal = ((MapServer.ROOT_LRLON - MapServer.ROOT_ULLON) / Math.pow(2, i))
                    + MapServer.ROOT_ULLON;
            lrlonArray[i] = lrlonVal;
            lrlatVal = MapServer.ROOT_ULLAT - ((MapServer.ROOT_ULLAT - MapServer.ROOT_LRLAT)
                    / Math.pow(2, i));
            lrlatArray[i] = lrlatVal;
        }
    }

    /**
     * Returns the depth level of tiles that should be displayed to the user.
     */
    private Integer findDepth(Double[] arrayOfLonDPPs, Double userLonDPP) {
        //Iterate through Double[] until you find a value that is less than
        // or equal to the user's dpp. If user's requested LonDPP is never smaller
        // than the any of the LonDPP's in the LonDPP Array,
        // return depth of 7.
        for (int i = 0; i < arrayOfLonDPPs.length; i++) {
            if (arrayOfLonDPPs[i] <= userLonDPP) {
                return i;
            }
        }
        return 7;
    }

    /**
     * At D (depth) level of zoom, there are 4^D images,
     * with names ranging from dD_x0_y0 to dD_xk_yk, where
     * k is 2^D - 1.
     *
     * Returns the index of the first tile to be displayed
     * in the horizontal direction
     */
    private Integer firstHorizTileIndex(Map<String, Double> params) {
        Double totalLon = lrlonArray[depth];
        Integer horizTiles = (int) Math.pow(2, depth);
        for (int i = 0; i < horizTiles; i++) {
            if (totalLon > params.get("ullon")) {
                return i;
            } else if (params.get("ullon") == totalLon) {
                return i + 1;
            }
            totalLon += lonWidth;
        }
        return 0;
    }

    /**
     * Returns the index of the first tile to be displayed
     * in the vertical direction
     */
    private Integer firstVertTileIndex(Map<String, Double> params) {
        Double totalLat = lrlatArray[depth];
        Integer vertTiles = (int) Math.pow(2, depth);
        for (int i = 0; i < vertTiles; i++) {
            if (totalLat < params.get("ullat")) {
                return i;
            } else if (params.get("ullat") == totalLat) {
                return i + 1;
            }
            totalLat -= latWidth;
        }
        return 0;
    }

    /**
     * Returns the number of tiles to be added in the horizontal direction.
     *
     * Invariant:
     *  1. There are 2^D - 1 tiles in the horizontal direction
     */
    private Integer numXTiles(Map<String, Double> params) {

        //find longitude of tile at firsHorizTileIndex
        Double lrlonOfFirstTile = MapServer.ROOT_ULLON + (lonWidth * (firstHorizTileIndex + 1));
        rasterullon = lrlonOfFirstTile - lonWidth;

        Double totalLon = lrlonOfFirstTile;
        Integer horizTiles = (int) Math.pow(2, depth);
        for (int i = 1; i < horizTiles; i++) {
            if (totalLon >= params.get("lrlon")) {
                rasterlrlon = totalLon;
                return i;
            }
            totalLon += lonWidth;
        }
        return 0;
    }

    /**
     * Returns the number of tiles to be added in the vertical
     * direction.
     */
    private Integer numYTiles(Map<String, Double> params) {

        //find latitude of tile at firstVertTileIndex
        Double lrlatOfFirstTile = MapServer.ROOT_ULLAT - (latWidth * (firstVertTileIndex + 1));
        rasterullat = lrlatOfFirstTile + latWidth;

        Double totalLat = lrlatOfFirstTile;
        Integer vertTiles = (int) Math.pow(2, depth);
        for (int i = 1; i < vertTiles; i++) {
            if (totalLat <= params.get("lrlat")) {
                rasterlrlat = totalLat;
                return i;
            }
            totalLat -= latWidth;
        }
        return 0;
    }

    /**
     * Adds tiles to render grid using the necessary information.
     */
    private void addToRenderGrid(Integer numXTiles, Integer numYTiles,
                                 Integer firstXIndex, Integer firstYIndex) {
        rendergrid = new String[numYTiles][numXTiles];
        for (int i = 0; i < numYTiles; i++) {
            for (int j = 0; j < numXTiles; j++) {
                rendergrid[i][j] = "d" + depth + "_x" + (firstXIndex + j) + "_y"
                        + (firstYIndex + i) + ".png";
            }
        }
    }
}
