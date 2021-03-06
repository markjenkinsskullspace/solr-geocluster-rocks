package ca.markjenkins.geoclusterrocks;

import ca.markjenkins.geoclusterrocks.GeoSearch;
import ca.markjenkins.geoclusterrocks.PointGroup;

import com.spatial4j.core.io.GeohashUtils;
import org.apache.lucene.util.SloppyMath;

import org.geojson.Feature;
import org.geojson.Point;
import org.geojson.LngLatAlt;

import java.util.Map;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.HashSet;
import java.util.Iterator;

import com.github.davidmoten.geo.GeoHash;
import com.github.davidmoten.geo.Direction;

public class Clustering {
    // this is pretty much strait lifted from
    // http://cgit.drupalcode.org/geocluster/tree/includes/GeoclusterHelper.inc
    public static final int ZOOMS = 30+1;
    
    public static final int PIXELS_PER_TILE = 256;

    public static final double EARTH_DIAMETER = SloppyMath.earthDiameter(0.0);
    public static final int METERS_PER_KM = 1000;

    // this is the most meters per pixel, what you get when viewing with world
    // with zoom=0
    // circumference = 2*PI*r = PI * diameter
    //
    // http://wiki.openstreetmap.org/wiki/Zoom_levels
    public static final double MAX_RESOLUTION =
	Math.PI * EARTH_DIAMETER * METERS_PER_KM / PIXELS_PER_TILE;

    public static final int GEOCLUSTER_DEFAULT_DISTANCE = 65;
    // we have to be careful with these,
    // in get_geohash_lengths_for_distance_threshold these prevent us from
    // computing and caching a wide range of user requesting distance
    // thresholds at 30 different zoom levels
    // if GEOCLUSTER_DEFAULT_DISTANCE == 65 we get
    // min==8, max=260, so that's 260-8+1=253 different tables each
    // containing 30 ints (4 bytes each)
    // so total memory 253*30*4 == 30360 bytes that the int arrays take up
    // and the HashMap geohash_lengths_for_zooms_per_threshold
    // is going to have 253 entries
    private static final int MIN_DISTANCE_THRESHOLD =
	GEOCLUSTER_DEFAULT_DISTANCE / 8;
    private static final int MAX_DISTANCE_THRESHOLD =
	GEOCLUSTER_DEFAULT_DISTANCE * 4;

    public static final double[] resolutions;
    private static final int[] default_geohash_lengths_for_zooms;
    public static Map<Integer, int[]> geohash_lengths_for_zooms_per_threshold;
    // as the following illustrates, a "final" array isn't so final...
    // in some situations this can be a security problem
    static {
	resolutions = new double[ZOOMS];
	for( int zoom=0; zoom < ZOOMS; zoom++ ){
	    // when zoom is 0, 2 to the power of 0 = 1,
	    // and this is MAX_RESOLUTION
	    resolutions[zoom] = MAX_RESOLUTION / Math.pow(2, zoom);
	}
	default_geohash_lengths_for_zooms = new int[ZOOMS];
	precompute_geohash_lengths
	    (default_geohash_lengths_for_zooms,
	     GEOCLUSTER_DEFAULT_DISTANCE);
	geohash_lengths_for_zooms_per_threshold =
	    new HashMap<Integer, int[]>();
	geohash_lengths_for_zooms_per_threshold
	    .put(GEOCLUSTER_DEFAULT_DISTANCE,
		 default_geohash_lengths_for_zooms);
    }

    public static final double RAD_TO_DEGREES = 180 / Math.PI;
    public static final int EARTH_AREA = 6378137;
    /**
     * Convert from meters (Spherical Mercator) to degrees (EPSG:4326).
     *
     * This is based on
http://cgit.drupalcode.org/geocluster/tree/includes/GeoclusterHelper.inc
     * 
     * which is based on
https://github.com/mapbox/clustr/blob/gh-pages/src/clustr.js
     *
     *  also see
http://dev.openlayers.org/docs/files/OpenLayers/Layer/SphericalMercator-js.html
https://github.com/openlayers/openlayers/blob/master/lib/OpenLayers/Projection.js#L278
    *
    * @return (lon, lat)
    */
    public static double[] backwardMercator(double x, double y) {
	double[] result = new double[2];
	result[0] = x * RAD_TO_DEGREES / EARTH_AREA;
	result[1] =
	    ((Math.PI * 0.5) - 2.0 *
	     Math.atan(Math.exp(-y / EARTH_AREA))
	     ) * RAD_TO_DEGREES;
	return result;
    }

    /**
     *
     */
    public static int[] get_geohash_lengths_for_distance_threshold
        (int distance_threshold){
	if (geohash_lengths_for_zooms_per_threshold
	    .containsKey(distance_threshold)){
	    return geohash_lengths_for_zooms_per_threshold
		.get(distance_threshold);
	}
	// we have to restrict distance_threshold or else we're allowing
	// users to increase our memory usage by adding entries to
	// geohash_lengths_for_zooms_per_threshold
	else if (distance_threshold > MAX_DISTANCE_THRESHOLD ||
		 distance_threshold < MIN_DISTANCE_THRESHOLD ){
	    // fixme, define an exception for this and handle better too
	    throw new RuntimeException("allowable distance thresholds exceeded");
	}
	// else, we have to compute geohash_lengths_for_zooms for this
	// threshold and saved for next time
	else {
	    int new_geohash_lengths_for_zoom[] = new int[ZOOMS];
	    precompute_geohash_lengths(new_geohash_lengths_for_zoom,
				       distance_threshold);
	    geohash_lengths_for_zooms_per_threshold
		.put(distance_threshold,new_geohash_lengths_for_zoom);
	    return new_geohash_lengths_for_zoom;
	}
    }

    /**
     * Calculate once the geohash lengths for a particular
     * distance threshold at all possible zoom levels (ZOOMS)
     *
     * The caller is assumed to be caching this so this need not be
     * calculated again
     */
    public static void precompute_geohash_lengths
	(int geohash_lengths_target[], int distance_threshold){
	double distance_threshold_double = distance_threshold;
	for( int zoom=0; zoom < ZOOMS; zoom++ ){
	    geohash_lengths_target[zoom] =
		lengthFromDistance(resolutions[zoom],
				   distance_threshold_double);
	}
    }
								     

    /**
     * Calculate geohash length for clustering by a specified distance
     * in pixels. This is based on lengthFromDistance() from 
http://cgit.drupalcode.org/geocluster/tree/includes/GeohashHelper.inc
     */
    public static int lengthFromDistance(double resolution,
                                         double distance_threshhold) {
	// this is the number of meters we'd like to keep our markers apart
	double cluster_distance_meters = distance_threshhold * resolution;
	double x = cluster_distance_meters;
	double y = cluster_distance_meters;
	double[] width_height = backwardMercator(x, y);

	int hashLen =
	    GeohashUtils.lookupHashLenForWidthHeight(width_height[0],
						     width_height[1] );
	return hashLen;
    }

    /**
     * Dumb implementation to incorporate pixel variation with latitude
     * on the mercator projection.
     *
     * @todo: use a valid implementation instead of the current guessing.
     *
     * Current implementation is based on the observation:
     * lat = 0 => output is correct
     * lat = 48 => output is 223 pixels distance instead of 335 in reality.
     *
     * derived from pixel_correction() from
     http://cgit.drupalcode.org/geocluster/tree/includes/GeoclusterHelper.inc
     */
    public static double pixel_correction(double lat) {
	return 1 + (335.0 / 223.271875276 - 1) * (Math.abs(lat) / 47.9899);
    }

    /**
     * Calculate the distance between two given points in pixels.
     *
     * This depends on the resolution (zoom level) they are viewed in.
     *
     * derived from distance_pixels() from 
http://cgit.drupalcode.org/geocluster/tree/includes/GeoclusterHelper.inc
     */
    public static double distance_pixels(Point a, Point b, double resolution){
	LngLatAlt a_coords = a.getCoordinates();
	LngLatAlt b_coords = b.getCoordinates();
	double a_latitude = a_coords.getLatitude();
	double distance = SloppyMath.haversin(a_latitude,
					      a_coords.getLongitude(),
					      b_coords.getLatitude(),
					      b_coords.getLongitude() );
	// instead of doing this correciton, I think there are more
	// sophisticated algorithms we can use for this distance to
	// pixel conversion that don't have this problem because we
	// feed them latitude to begin with
	return distance / resolution * pixel_correction(a_latitude);
    }

    public static boolean shouldCluster(Point a, Point b, double resolution,
                                        double distance_threshold) {
	// Calculate distance.
	return distance_pixels(a, b, resolution) <= distance_threshold;
    }
    /* Derived from
     * http://cgit.drupalcode.org/geocluster/tree/includes/GeohashHelper.inc
     */
    public static String[] getTopRightNeighbors(String geohash) {
	// Return only top-right neighbors according to the structure of geohash.
	String[] neighbors = new String[4];
	String top = GeoHash.top(geohash);
	neighbors[0] = GeoHash.left(top);
	neighbors[1] = top;
	neighbors[2] = GeoHash.right(top);
	neighbors[3] = GeoHash.right(geohash);
	return neighbors;
    }

    /**
     * Create final clusters by checking for overlapping neighbors.
     *
     * Derived from
http://cgit.drupalcode.org/geocluster/tree/plugins/algorithm/GeohashGeoclusterAlgorithm.inc
     */
    static void clusterByNeighborCheck
	(Map<String, PointGroup> geohash_groups, int zoom) {
	
	double resolution = resolutions[zoom];

	// whoa, big assumption here, we're assuming because geohash_groups
	// is actually a TreeMap or LinkedHashMap that we're getting keys in
	// order here....
	// otherwise we have to do some detecting and casting to work with
	// both cases...
	//
	// Plus our performance is suffering for it, we're having to maintain
	// a set of hashes already merged to ignore and them removeing them
	// after instead of modifying the set while we work it over in one
	// pass... surely the more deep apis of TreeMaps and LinkedHashMap
	// would allow us to iterate and remove at the same time
	HashSet<String> already_removed_hashes = new HashSet<String>();
	for (String item_hash: geohash_groups.keySet() ){

	    // ignore hash already "removed"
	    if(already_removed_hashes.contains(item_hash))
		continue;

	    PointGroup item = geohash_groups.get(item_hash);
	    Point geometry = (Point) item.getGeometry();
	    
	    // Check top right neighbor hashes for overlapping points.
	    // Top-right is enough because by the way geohash is structured,
	    // future geohashes are always top, topright or right
	    String[] neighbours = getTopRightNeighbors(item_hash);
	    String all_neighbours = "";
	    String removed = "";
	    for (int i=0; i<neighbours.length; i++){
		String other_hash = neighbours[i];
		if (already_removed_hashes.contains(other_hash))
		    continue;
		all_neighbours += " " + other_hash;
		PointGroup other_item = geohash_groups.get(other_hash);
		if (other_item != null){
		    Point other_geometry = (Point) other_item.getGeometry();

		    if (shouldCluster(geometry, other_geometry, resolution,
				      GEOCLUSTER_DEFAULT_DISTANCE)) {
			assert( null != item.getGeometry() );
			if (item.cluster_collection()){
			    assert( null !=
				    item.get_cluster_feature().getGeometry() );
			}
			assert( null != other_item.getGeometry() );
			item.mergeIn(other_item);
			assert( null != item.getGeometry() );
			if (item.cluster_collection()){
			    assert( null !=
				    item.get_cluster_feature().getGeometry() );
			}

			already_removed_hashes.add(other_hash);
			removed += " " + other_hash;
		    }
		}
	    }
	}
	// now we do the actual removing
	for (String remove_hash: already_removed_hashes)
	    geohash_groups.remove(remove_hash);
    }

}
