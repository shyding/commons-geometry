/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.geometry.spherical.twod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.geometry.core.Geometry;
import org.apache.commons.geometry.core.partitioning.Hyperplane;
import org.apache.commons.geometry.core.partitioning.Split;
import org.apache.commons.geometry.core.partitioning.bsp.AbstractBSPTree;
import org.apache.commons.geometry.core.partitioning.bsp.AbstractRegionBSPTree;
import org.apache.commons.geometry.core.precision.DoublePrecisionContext;
import org.apache.commons.geometry.euclidean.threed.Vector3D;

/** BSP tree representing regions in 2D spherical space.
 */
public class RegionBSPTree2S extends AbstractRegionBSPTree<Point2S, RegionBSPTree2S.RegionNode2S> {
    /** Constant containing the area of the full spherical space. */
    private static final double FULL_SIZE = 4 * Geometry.PI;

    /** List of great arc path comprising the region boundary. */
    private List<GreatArcPath> boundaryPaths;

    /** Create a new, empty instance.
     */
    public RegionBSPTree2S() {
        this(false);
    }

    /** Create a new region. If {@code full} is true, then the region will
     * represent the entire 2-sphere. Otherwise, it will be empty.
     * @param full whether or not the region should contain the entire
     *      2-sphere or be empty
     */
    public RegionBSPTree2S(boolean full) {
        super(full);
    }

    /** Return a deep copy of this instance.
     * @return a deep copy of this instance.
     * @see #copy(org.apache.commons.geometry.core.partitioning.bsp.BSPTree)
     */
    public RegionBSPTree2S copy() {
        RegionBSPTree2S result = RegionBSPTree2S.empty();
        result.copy(this);

        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Iterable<GreatArc> boundaries() {
        return createBoundaryIterable(b -> (GreatArc) b);
    }

    /** {@inheritDoc} */
    @Override
    public List<GreatArc> getBoundaries() {
        return createBoundaryList(b -> (GreatArc) b);
    }

    /** Get the boundary of the region as a list of connected great arc paths. The
     * arcs are oriented such that their minus (left) side lies on the interior of
     * the region.
     * @return great arc paths representing the region boundary
     */
    public List<GreatArcPath> getBoundaryPaths() {
        if (boundaryPaths == null) {
            boundaryPaths = Collections.unmodifiableList(computeBoundaryPaths());
        }
        return boundaryPaths;
    }

    /** Return a list of {@link ConvexArea2S}s representing the same region
     * as this instance. One convex area is returned for each interior leaf
     * node in the tree.
     * @return a list of convex areas representing the same region as this
     *      instance
     */
    public List<ConvexArea2S> toConvex() {
        final List<ConvexArea2S> result = new ArrayList<>();

        toConvexRecursive(getRoot(), ConvexArea2S.full(), result);

        return result;
    }

    /** Recursive method to compute the convex areas of all inside leaf nodes in the subtree rooted at the given
     * node. The computed convex areas are added to the given list.
     * @param node root of the subtree to compute the convex areas for
     * @param nodeArea the convex area for the current node; this will be split by the node's cut hyperplane to
     *      form the convex areas for any child nodes
     * @param result list containing the results of the computation
     */
    private void toConvexRecursive(final RegionNode2S node, final ConvexArea2S nodeArea,
            final List<ConvexArea2S> result) {
        if (node.isLeaf()) {
            // base case; only add to the result list if the node is inside
            if (node.isInside()) {
                result.add(nodeArea);
            }
        } else {
            // recurse
            Split<ConvexArea2S> split = nodeArea.split(node.getCutHyperplane());

            toConvexRecursive(node.getMinus(), split.getMinus(), result);
            toConvexRecursive(node.getPlus(), split.getPlus(), result);
        }
    }

    /** {@inheritDoc} */
    @Override
    public Split<RegionBSPTree2S> split(final Hyperplane<Point2S> splitter) {
        return split(splitter, empty(), empty());
    }

    /** {@inheritDoc} */
    @Override
    public Point2S project(final Point2S pt) {
        // use our custom projector so that we can disambiguate points that are
        // actually equidistant from the target point
        final BoundaryProjector2S projector = new BoundaryProjector2S(pt);
        accept(projector);

        return projector.getProjected();
    }

    /** {@inheritDoc} */
    @Override
    protected RegionSizeProperties<Point2S> computeRegionSizeProperties() {
        // handle simple cases
        if (isFull()) {
            return new RegionSizeProperties<>(FULL_SIZE, null);
        } else if (isEmpty()) {
            return new RegionSizeProperties<>(0, null);
        }

        List<ConvexArea2S> areas = toConvex();
        DoublePrecisionContext precision = ((GreatArc) getRoot().getCut()).getPrecision();

        double sizeSum = 0;
        Vector3D barycenterVector = Vector3D.ZERO;

        double size;
        for (ConvexArea2S area : areas) {
            size = area.getSize();

            sizeSum += size;
            barycenterVector = Vector3D.linearCombination(
                    1, barycenterVector,
                    size, area.getBarycenter().getVector());
        }

        Point2S barycenter = barycenterVector.eq(Vector3D.ZERO, precision) ?
                null :
                Point2S.from(barycenterVector);

        return new RegionSizeProperties<>(sizeSum, barycenter);
    }

    /** {@inheritDoc} */
    @Override
    protected RegionNode2S createNode() {
        return new RegionNode2S(this);
    }

    /** {@inheritDoc} */
    @Override
    protected void invalidate() {
        super.invalidate();

        boundaryPaths = null;
    }

    /** Compute the region represented by the given node.
     * @param node the node to compute the region for
     * @return the region represented by the given node
     */
    private ConvexArea2S computeNodeRegion(final RegionNode2S node) {
        ConvexArea2S area = ConvexArea2S.full();

        RegionNode2S child = node;
        RegionNode2S parent;

        while ((parent = child.getParent()) != null) {
            Split<ConvexArea2S> split = area.split(parent.getCutHyperplane());

            area = child.isMinus() ? split.getMinus() : split.getPlus();

            child = parent;
        }

        return area;
    }

    /** Compute the great arc paths comprising the region boundary.
     * @return the great arc paths comprising the region boundary
     */
    private List<GreatArcPath> computeBoundaryPaths() {
        final InteriorAngleGreatArcConnector connector = new InteriorAngleGreatArcConnector.Minimize();
        return connector.connectAll(boundaries());
    }

    /** Return a new, empty BSP tree.
     * @return a new, empty BSP tree.
     */
    public static RegionBSPTree2S empty() {
        return new RegionBSPTree2S(false);
    }

    /** Return a new, full BSP tree. The returned tree represents the
     * full space.
     * @return a new, full BSP tree.
     */
    public static RegionBSPTree2S full() {
        return new RegionBSPTree2S(true);
    }

    /** Construct a tree from a convex area.
     * @param area the area to construct a tree from
     * @return tree instance representing the same area as the given
     *      convex area
     */
    public static RegionBSPTree2S from(final ConvexArea2S area) {
        final RegionBSPTree2S tree = RegionBSPTree2S.full();
        tree.insert(area.getBoundaries());

        return tree;
    }

    /** BSP tree node for two dimensional spherical space.
     */
    public static final class RegionNode2S extends AbstractRegionBSPTree.AbstractRegionNode<Point2S, RegionNode2S> {
        /** Simple constructor.
         * @param tree tree owning the instance.
         */
        protected RegionNode2S(final AbstractBSPTree<Point2S, RegionNode2S> tree) {
            super(tree);
        }

        /** Get the region represented by this node. The returned region contains
         * the entire area contained in this node, regardless of the attributes of
         * any child nodes.
         * @return the region represented by this node
         */
        public ConvexArea2S getNodeRegion() {
            return ((RegionBSPTree2S) getTree()).computeNodeRegion(this);
        }

        /** {@inheritDoc} */
        @Override
        protected RegionNode2S getSelf() {
            return this;
        }
    }

    /** Class used to project points onto the region boundary.
     */
    private static final class BoundaryProjector2S extends BoundaryProjector<Point2S, RegionNode2S> {
        /** Simple constructor.
         * @param point the point to project onto the region's boundary
         */
        BoundaryProjector2S(Point2S point) {
            super(point);
        }

        /** {@inheritDoc} */
        @Override
        protected Point2S disambiguateClosestPoint(final Point2S target, final Point2S a, final Point2S b) {
            // return the point with the smallest coordinate values
            final int cmp = Point2S.POLAR_AZIMUTH_ASCENDING_ORDER.compare(a, b);
            return cmp < 0 ? a : b;
        }
    }
}
