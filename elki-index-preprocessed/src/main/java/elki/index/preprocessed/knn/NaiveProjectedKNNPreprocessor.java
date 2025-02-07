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
package elki.index.preprocessed.knn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import elki.data.DoubleVector;
import elki.data.NumberVector;
import elki.data.projection.random.RandomProjectionFamily;
import elki.data.type.TypeInformation;
import elki.data.type.TypeUtil;
import elki.database.datastore.DataStoreFactory;
import elki.database.datastore.DataStoreUtil;
import elki.database.datastore.WritableDataStore;
import elki.database.ids.*;
import elki.database.query.QueryBuilder;
import elki.database.query.distance.DistanceQuery;
import elki.database.query.knn.KNNSearcher;
import elki.database.relation.Relation;
import elki.database.relation.RelationUtil;
import elki.index.IndexFactory;
import elki.index.KNNIndex;
import elki.logging.Logging;
import elki.logging.statistics.DoubleStatistic;
import elki.math.Mean;
import elki.utilities.documentation.Reference;
import elki.utilities.optionhandling.OptionID;
import elki.utilities.optionhandling.Parameterizer;
import elki.utilities.optionhandling.constraints.CommonConstraints;
import elki.utilities.optionhandling.parameterization.Parameterization;
import elki.utilities.optionhandling.parameters.DoubleParameter;
import elki.utilities.optionhandling.parameters.IntParameter;
import elki.utilities.optionhandling.parameters.ObjectParameter;
import elki.utilities.optionhandling.parameters.RandomParameter;
import elki.utilities.random.RandomFactory;

/**
 * Compute the approximate k nearest neighbors using 1 dimensional projections.
 * <p>
 * This serves as a comparison method in:
 * <p>
 * Erich Schubert, Arthur Zimek, Hans-Peter Kriegel<br>
 * Fast and Scalable Outlier Detection with Approximate Nearest Neighbor
 * Ensembles<br>
 * Proc. 20th Int. Conf. Database Systems for Advanced Applications
 * (DASFAA 2015)
 *
 * @author Erich Schubert
 * @since 0.7.0
 * @has - - - NaiveProjectedKNNQuery
 */
@Reference(authors = "Erich Schubert, Arthur Zimek, Hans-Peter Kriegel", //
    title = "Fast and Scalable Outlier Detection with Approximate Nearest Neighbor Ensembles", //
    booktitle = "Proc. 20th Int. Conf. Database Systems for Advanced Applications (DASFAA 2015)", //
    url = "https://doi.org/10.1007/978-3-319-18123-3_2", //
    bibkey = "DBLP:conf/dasfaa/SchubertZK15")
public class NaiveProjectedKNNPreprocessor<O extends NumberVector> implements KNNIndex<O> {
  /**
   * The representation we are bound to.
   */
  protected final Relation<O> relation;

  /**
   * Class logger.
   */
  private static final Logging LOG = Logging.getLogger(NaiveProjectedKNNPreprocessor.class);

  /**
   * Curve window size.
   */
  final double window;

  /**
   * Number of projections to use.
   */
  final int projections;

  /**
   * Curve position storage
   */
  WritableDataStore<int[]> positions = null;

  /**
   * Mean number of distance computations.
   */
  Mean mean = new Mean();

  /**
   * Random projection family to use.
   */
  RandomProjectionFamily proj;

  /**
   * Random number generator.
   */
  Random random;

  /**
   * Projected data.
   */
  List<ModifiableDoubleDBIDList> projected;

  /**
   * Constructor.
   *
   * @param relation Relation to index.
   * @param window Window multiplicator.
   * @param projections Number of projections to use.
   * @param proj Projection family to use.
   * @param random Random number generator.
   */
  public NaiveProjectedKNNPreprocessor(Relation<O> relation, double window, int projections, RandomProjectionFamily proj, Random random) {
    super();
    this.relation = relation;
    this.window = window;
    this.projections = projections;
    this.proj = proj;
    this.random = random;
  }

  @Override
  public void initialize() {
    if(positions != null) {
      throw new UnsupportedOperationException("Preprocessor already ran.");
    }
    if(relation.size() > 0) {
      preprocess();
    }
  }

  protected void preprocess() {
    final long starttime = System.nanoTime();
    final int size = relation.size();
    final int idim = RelationUtil.dimensionality(relation);
    final int odim = (projections > 0) ? projections : idim;

    projected = new ArrayList<>(odim);
    for(int j = 0; j < odim; j++) {
      projected.add(DBIDUtil.newDistanceDBIDList(size));
    }

    if(proj == null) {
      // Generate permutation:
      final int[] permutation = range(0, idim);
      if(odim < idim) {
        randomPermutation(permutation, random);
      }

      for(DBIDIter iditer = relation.iterDBIDs(); iditer.valid(); iditer.advance()) {
        final NumberVector v = relation.get(iditer);
        for(int j = 0; j < odim; j++) {
          projected.get(j).add(v.doubleValue(permutation[j]), iditer);
        }
      }
    }
    else {
      final RandomProjectionFamily.Projection mat = proj.generateProjection(idim, odim);
      for(DBIDIter iditer = relation.iterDBIDs(); iditer.valid(); iditer.advance()) {
        double[] v = mat.project(relation.get(iditer));
        for(int j = 0; j < odim; j++) {
          projected.get(j).add(v[j], iditer);
        }
      }
    }
    // Sort
    for(int j = 0; j < odim; j++) {
      projected.get(j).sort();
    }

    // Build position index, DBID -> position in the three curves
    positions = DataStoreUtil.makeStorage(relation.getDBIDs(), DataStoreFactory.HINT_TEMP | DataStoreFactory.HINT_HOT, int[].class);
    for(int cnum = 0; cnum < odim; cnum++) {
      DoubleDBIDListIter it = projected.get(cnum).iter();
      for(int i = 0; it.valid(); i++, it.advance()) {
        final int[] data;
        if(cnum == 0) {
          data = new int[odim];
          positions.put(it, data);
        }
        else {
          data = positions.get(it);
        }
        data[cnum] = i;
      }
    }
    final long end = System.nanoTime();
    if(LOG.isVerbose()) {
      LOG.verbose("SFC preprocessor took " + ((end - starttime) / 1.E6) + " milliseconds.");
    }
  }

  /**
   * Initialize an integer value range.
   *
   * @param start Starting value
   * @param end End value (exclusive)
   * @return Array of integers start..end, excluding end.
   */
  public static int[] range(int start, int end) {
    int[] out = new int[end - start];
    for(int i = 0, j = start; j < end; i++, j++) {
      out[i] = j;
    }
    return out;
  }

  /**
   * Perform a random permutation of the array, in-place.
   *
   * Knuth / Fisher-Yates style shuffle
   *
   * @param out Prefilled array to be modified.
   * @param random Random generator.
   * @return Same array.
   */
  public static int[] randomPermutation(final int[] out, Random random) {
    for(int i = out.length - 1; i > 0; i--) {
      // Swap with random preceeding element.
      int ri = random.nextInt(i + 1);
      int tmp = out[ri];
      out[ri] = out[i];
      out[i] = tmp;
    }
    return out;
  }

  @Override
  public String getLongName() {
    return "Space Filling Curve KNN preprocessor";
  }

  @Override
  public String getShortName() {
    return "spacefilling-knn";
  }

  @Override
  public void logStatistics() {
    LOG.statistics(new DoubleStatistic(this.getClass().getCanonicalName() + ".distance-computations-per-k", mean.getMean()));
  }

  @Override
  public KNNSearcher<O> kNNByObject(DistanceQuery<O> distanceQuery, int maxk, int flags) {
    return null; // FIXME: add support for this?
  }

  @Override
  public KNNSearcher<DBIDRef> kNNByDBID(DistanceQuery<O> distanceQuery, int maxk, int flags) {
    return (flags & QueryBuilder.FLAG_EXACT_ONLY) != 0 ? null : // approximate
        new NaiveProjectedKNNQuery(distanceQuery);
  }

  /**
   * KNN Query processor for naive projections.
   *
   * @author Erich Schubert
   */
  protected class NaiveProjectedKNNQuery implements KNNSearcher<DBIDRef> {
    /**
     * Distance query to use for refinement
     */
    DistanceQuery<O> distq;

    /**
     * Constructor.
     *
     * @param distanceQuery Distance query to use for refinement
     */
    public NaiveProjectedKNNQuery(DistanceQuery<O> distanceQuery) {
      super();
      this.distq = distanceQuery;
    }

    @Override
    public KNNList getKNN(DBIDRef id, int k) {
      final int wsize = (int) Math.ceil(window * k);
      // Build candidates
      ModifiableDBIDs cands = DBIDUtil.newHashSet(2 * wsize * projected.size());
      final int[] posi = positions.get(id);
      for(int i = 0; i < posi.length; i++) {
        DoubleDBIDListIter it = projected.get(i).iter();
        it.seek(Math.max(0, posi[i] - wsize));
        for(int j = (wsize << 1); j >= 0 && it.valid(); j--, it.advance()) {
          cands.add(it);
        }
      }
      // Refine:
      int distc = 0;
      KNNHeap heap = DBIDUtil.newHeap(k);
      final O vec = relation.get(id);
      for(DBIDIter iter = cands.iter(); iter.valid(); iter.advance()) {
        heap.insert(distq.distance(vec, iter), iter);
        distc++;
      }
      mean.put(distc / (double) k);
      return heap.toKNNList();
    }
  }

  /**
   * Index factory class
   *
   * @author Erich Schubert
   *
   * @param <V> Vector type
   *
   * @has - - - NaiveProjectedKNNPreprocessor
   */
  public static class Factory<V extends NumberVector> implements IndexFactory<V> {
    /**
     * Curve window size
     */
    double window;

    /**
     * Number of projections to use.
     */
    int projections;

    /**
     * Random projection family to use.
     */
    RandomProjectionFamily proj;

    /**
     * Random number generator.
     */
    RandomFactory random;

    /**
     * Constructor.
     *
     * @param window Window multiplicator.
     * @param projections Number of projections to use.
     * @param proj Projection family to use.
     * @param random Random number generator.
     */
    public Factory(double window, int projections, RandomProjectionFamily proj, RandomFactory random) {
      super();
      this.window = window;
      this.projections = projections;
      this.proj = proj;
      this.random = random;
    }

    @Override
    public NaiveProjectedKNNPreprocessor<V> instantiate(Relation<V> relation) {
      return new NaiveProjectedKNNPreprocessor<>(relation, window, projections, proj, random.getRandom());
    }

    @Override
    public TypeInformation getInputTypeRestriction() {
      return TypeUtil.NUMBER_VECTOR_FIELD;
    }

    /**
     * Parameterization class.
     *
     * @author Erich Schubert
     */
    public static class Par implements Parameterizer {
      /**
       * Parameter for setting the widows size multiplicator.
       */
      public static final OptionID WINDOW_ID = new OptionID("projections.windowmult", "Window size multiplicator.");

      /**
       * Parameter for choosing the number of projections to use.
       */
      public static final OptionID PROJECTIONS_ID = new OptionID("projections.projections", "Number of projections to use.");

      /**
       * Parameter for choosing the random projections.
       */
      public static final OptionID PROJECTION_ID = new OptionID("projections.family", "Random projection family to use. The default is to use the original axes.");

      /**
       * Parameter for choosing the number of variants to use.
       */
      public static final OptionID RANDOM_ID = new OptionID("projections.seed", "Random generator.");

      /**
       * Curve window size.
       */
      double window;

      /**
       * Number of projections to use.
       */
      int projections = -1;

      /**
       * Random projection family to use.
       */
      RandomProjectionFamily proj;

      /**
       * Random number generator.
       */
      RandomFactory random;

      @Override
      public void configure(Parameterization config) {
        new DoubleParameter(WINDOW_ID, 10.0).grab(config, x -> window = x);
        new IntParameter(PROJECTIONS_ID) //
            .setOptional(true) //
            .addConstraint(CommonConstraints.GREATER_EQUAL_ONE_INT) //
            .grab(config, x -> projections = x);
        new ObjectParameter<RandomProjectionFamily>(PROJECTION_ID, RandomProjectionFamily.class) //
            .setOptional(true) //
            .grab(config, x -> proj = x);
        new RandomParameter(RANDOM_ID).grab(config, x -> random = x);
      }

      @Override
      public Factory<?> make() {
        return new Factory<DoubleVector>(window, projections, proj, random);
      }
    }
  }
}
