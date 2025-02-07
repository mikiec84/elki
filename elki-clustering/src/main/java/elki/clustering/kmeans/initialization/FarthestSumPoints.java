/*
 * This file is part of ELKI:
 * Environment for Developing KDD-Applications Supported by Index-Structures
 *
 * Copyright (C) 2019
 * ELKI Development Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package elki.clustering.kmeans.initialization;

import java.util.ArrayList;
import java.util.List;

import elki.data.NumberVector;
import elki.database.datastore.DataStoreFactory;
import elki.database.datastore.DataStoreUtil;
import elki.database.datastore.WritableDoubleDataStore;
import elki.database.ids.ArrayModifiableDBIDs;
import elki.database.ids.DBIDIter;
import elki.database.ids.DBIDRef;
import elki.database.ids.DBIDUtil;
import elki.database.ids.DBIDVar;
import elki.database.ids.DBIDs;
import elki.database.query.distance.DistanceQuery;
import elki.database.relation.Relation;
import elki.distance.NumberVectorDistance;
import elki.utilities.random.RandomFactory;

/**
 * K-Means initialization by repeatedly choosing the farthest point (by the
 * <em>sum</em> of distances to previous objects).
 *
 * Note: this is less random than other initializations, so running multiple
 * times will be more likely to return the same local minima.
 *
 * @author Erich Schubert
 * @since 0.6.0
 *
 * @param <O> Object type for kmedoids and kmedians
 */
public class FarthestSumPoints<O> extends FarthestPoints<O> {
  /**
   * Constructor.
   *
   * @param rnd Random generator.
   * @param dropfirst Flag to discard the first vector.
   */
  public FarthestSumPoints(RandomFactory rnd, boolean dropfirst) {
    super(rnd, dropfirst);
  }

  @Override
  public double[][] chooseInitialMeans(Relation<? extends NumberVector> relation, int k, NumberVectorDistance<?> distance) {
    if(relation.size() < k) {
      throw new IllegalArgumentException("Cannot choose k=" + k + " means from N=" + relation.size() + " < k objects.");
    }
    DBIDs ids = relation.getDBIDs();
    WritableDoubleDataStore store = DataStoreUtil.makeDoubleStorage(ids, DataStoreFactory.HINT_HOT | DataStoreFactory.HINT_TEMP, 0.);

    List<NumberVector> means = new ArrayList<>(k);

    DBIDRef first = DBIDUtil.randomSample(ids, rnd);
    NumberVector prevmean = relation.get(first);
    means.add(prevmean);

    // Find farthest object each.
    DBIDVar best = DBIDUtil.newVar(first);
    for(int i = (dropfirst ? 0 : 1); i < k; i++) {
      double maxdist = Double.NEGATIVE_INFINITY;
      for(DBIDIter it = ids.iter(); it.valid(); it.advance()) {
        final double prev = store.doubleValue(it);
        if(prev != prev) {
          continue; // NaN: already chosen!
        }
        double dsum = prev + distance.distance(prevmean, relation.get(it));
        // Don't store distance to first mean, when it will be dropped below.
        if(i > 0) {
          store.putDouble(it, dsum);
        }
        if(dsum > maxdist) {
          maxdist = dsum;
          best.set(it);
        }
      }
      // Add new mean (and drop the initial mean when desired)
      if(i == 0) {
        means.clear(); // Remove temporary first element.
      }
      store.putDouble(best, Double.NaN); // So it won't be chosen twice.
      prevmean = relation.get(best);
      means.add(prevmean);
    }

    // Explicitly destroy temporary data.
    store.destroy();
    return unboxVectors(means);
  }

  @Override
  public DBIDs chooseInitialMedoids(int k, DBIDs ids, DistanceQuery<? super O> distQ) {
    @SuppressWarnings("unchecked")
    final Relation<O> relation = (Relation<O>) distQ.getRelation();

    WritableDoubleDataStore store = DataStoreUtil.makeDoubleStorage(ids, DataStoreFactory.HINT_HOT | DataStoreFactory.HINT_TEMP, 0.);

    ArrayModifiableDBIDs means = DBIDUtil.newArray(k);

    DBIDRef first = DBIDUtil.randomSample(ids, rnd);
    means.add(first);
    DBIDVar prevmean = DBIDUtil.newVar(first);

    DBIDVar best = DBIDUtil.newVar(first);
    for(int i = (dropfirst ? 0 : 1); i < k; i++) {
      // Find farthest object:
      double maxdist = Double.NEGATIVE_INFINITY;
      for(DBIDIter it = relation.iterDBIDs(); it.valid(); it.advance()) {
        final double prev = store.doubleValue(it);
        if(prev != prev) {
          continue; // NaN: already chosen!
        }
        double dsum = prev + distQ.distance(prevmean, it);
        // Don't store distance to first mean, when it will be dropped below.
        if(i > 0) {
          store.putDouble(it, dsum);
        }
        if(dsum > maxdist) {
          maxdist = dsum;
          best.set(it);
        }
      }
      // Add new mean:
      if(i == 0) {
        means.clear(); // Remove temporary first element.
      }
      store.putDouble(best, Double.NaN); // So it won't be chosen twice.
      prevmean.set(best);
      means.add(best);
    }

    store.destroy();
    return means;
  }

  /**
   * Parameterization class.
   *
   * @author Erich Schubert
   */
  public static class Par<V> extends FarthestPoints.Par<V> {
    /**
     * Flag for discarding the first object chosen.
     */
    protected boolean keepfirst = false;

    @Override
    public FarthestSumPoints<V> make() {
      return new FarthestSumPoints<>(rnd, !keepfirst);
    }
  }
}
