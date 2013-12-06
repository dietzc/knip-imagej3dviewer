/*
 * ------------------------------------------------------------------------
 *
 *  Copyright (C) 2003 - 2013
 *  University of Konstanz, Germany and
 *  KNIME GmbH, Konstanz, Germany
 *  Website: http://www.knime.org; Email: contact@knime.org
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME GMBH herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * --------------------------------------------------------------------- *
 *
 */
package org.knime.knip.imagej3d;

import ij.ImagePlus;
import ij.process.StackConverter;
import ij3d.Content;
import ij3d.ContentConstants;
import ij3d.Image3DUniverse;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.ToolTipManager;

import net.imglib2.converter.read.ConvertedRandomAccessibleInterval;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.ImgView;
import net.imglib2.meta.ImgPlus;
import net.imglib2.ops.operation.Operations;
import net.imglib2.ops.operation.real.unary.Convert;
import net.imglib2.ops.operation.real.unary.Convert.TypeConversionTypes;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.ByteType;

import org.knime.core.data.DataValue;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.config.ConfigRO;
import org.knime.core.node.config.ConfigWO;
import org.knime.knip.base.data.img.ImgPlusValue;
import org.knime.knip.base.nodes.view.TableCellView;
import org.knime.knip.imagej2.core.util.ImgToIJ;
import org.knime.knip.imagej2.core.util.UntransformableIJTypeException;

import view4d.Timeline;
import view4d.TimelineGUI;

/* Helper class for the IJ3D viewer, provides the TableCellView
 * @author <a href="mailto:gabriel.einsdorf@uni.kn">Gabriel Einsdorf</a>
 */
public class ImageJ3DTableCellView<T extends RealType<T>> implements
		TableCellView {

	private NodeLogger logger = NodeLogger
			.getLogger(ImageJ3DTableCellView.class);

	// Default rendering Type
	private final int renderType = ContentConstants.VOLUME; // TODO

	// 4D stuff
	private Timeline timeline;
	private TimelineGUI timelineGUI;
	private JPanel panel4D = new JPanel();

	// rendering Universe
	private Image3DUniverse universe;

	// Container for the converted picture,
	private ImagePlus ijImagePlus;

	// ui containers
	private JPanel rootPanel;
	private JTextArea errorPanel;

	// Stores the Image that the viewer displays
	private DataValue dataValue;

	/**
	 * 
	 * @return the immage the viewer is displaying
	 */

	public final DataValue getDataValue() {
		return dataValue;
	}

	// Container for picture
	private Content c;

	// Creates a viewer which will be updated on "updateComponent"
	@Override
	public final Component getViewComponent() {
		// Fixes Canvas covering menu
		JPopupMenu.setDefaultLightWeightPopupEnabled(false);
		ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);

		// universe for rendering the image
		universe = new Image3DUniverse();
		timeline = universe.getTimeline();

		// Container for the viewComponent
		rootPanel = new JPanel(new BorderLayout());

		// Panel for error messages
		errorPanel = new JTextArea("");
		errorPanel.setVisible(false);

		// Menubar
		ImageJ3DMenubar<T> ij3dbar = new ImageJ3DMenubar<T>(universe, this);
		// add menubar and 3Duniverse to the panel

		// rootPanel.add(errorPanel);
		rootPanel.add(ij3dbar, BorderLayout.NORTH);
		rootPanel.add(universe.getCanvas(0), BorderLayout.CENTER);

		return rootPanel;
	}

	/**
	 * updates the Component, called whenever a new picture is selected, or view
	 * is reset.
	 * 
	 * @param The
	 *            ImgPlus that is to be displayed by the viewer.
	 */
	@SuppressWarnings("unchecked")
	@Override
	public final void updateComponent(final DataValue valueToView) {
		// New image arrives
		universe.resetView();
		universe.removeAllContents(); // cleanup universe

		dataValue = valueToView;

		for (Component t : rootPanel.getComponents()) {
			t.setVisible(true);
		}
		errorPanel.setVisible(false);
		rootPanel.remove(errorPanel);

		ImgPlus<T> in = ((ImgPlusValue<T>) valueToView).getImgPlus();

		// escapes
		if (in.numDimensions() > 5) {
			logger.warn("Error: only immages with up to 5 Dimensions are supported by the 3D viewer");
			return;
		}

		ImgPlus<ByteType> imgPlus = null;
		final T firstElement = in.firstElement();
		if (!(firstElement instanceof ByteType)) {
			try {
				ConvertedRandomAccessibleInterval<T, ByteType> converted = new ConvertedRandomAccessibleInterval<T, ByteType>(
						in, new Convert<T, ByteType>(firstElement,
								new ByteType(), TypeConversionTypes.SCALE),
						new ByteType());

				imgPlus = new ImgPlus<ByteType>(new ImgView<ByteType>(
						converted, in.factory().imgFactory(new ByteType())), in);

			} catch (IncompatibleTypeException e) {
				unsurportedType(firstElement.toString(), in.getName());
			}
		} else {
			imgPlus = (ImgPlus<ByteType>) in;
		}

		// if (firstElement instanceof FloatType) {
		// unsurportedType("FloatType", imgPlus.getName());
		// return;
		// }
		// if (firstElement instanceof UnsignedByteType) {
		// unsurportedType("UnsignedByteType", imgPlus.getName());
		// return;
		// }
		// if (firstElement instanceof ShortType) {
		// unsurportedType("ShortType", imgPlus.getName());
		// return;
		// }

		ImgToIJ imgToIJ = new ImgToIJ(imgPlus.numDimensions());

		// validate if mapping can be inferred automatically
		if (!imgToIJ.validateMapping(imgPlus)) {
			if (!imgToIJ.inferMapping(imgPlus)) {
				logger.warn("Warning: couldn't match dimensions of input picture");
				return;
			}
		}
		// convert to I
		try {
			ijImagePlus = Operations.compute(imgToIJ, imgPlus);
		} catch (UntransformableIJTypeException e) {
			for (Component t : rootPanel.getComponents()) {
				t.setVisible(false);
			}
			errorPanel.setVisible(true);
			rootPanel.add(errorPanel);
			errorPanel.setText("Can't convert picture!");
			logger.warn("Can't convert the picture: " + imgPlus.getName());
			return;
		}
		try {
			new StackConverter(ijImagePlus).convertToGray8();
		} catch (java.lang.IllegalArgumentException e) {
			for (Component t : rootPanel.getComponents()) {
				t.setVisible(false);
			}
			errorPanel.setVisible(true);
			rootPanel.add(errorPanel);

			errorPanel.setText("Can't convert picture!");
			logger.warn("couldnt convert the picture: " + imgPlus.getName());
			return;
		}
		switch (renderType) {
		case ContentConstants.ORTHO:
			c = universe.addOrthoslice(ijImagePlus);
			break;
		case ContentConstants.MULTIORTHO:
			c = universe.addOrthoslice(ijImagePlus);
			c.displayAs(ContentConstants.MULTIORTHO);
		case ContentConstants.VOLUME:
			c = universe.addVoltex(ijImagePlus);
			break;
		case ContentConstants.SURFACE:
			c = universe.addMesh(ijImagePlus);
			break;
		case ContentConstants.SURFACE_PLOT2D:
			c = universe.addSurfacePlot(ijImagePlus);
			break;
		}
		universe.updateTimeline();

		// enables the timeline gui if picture has 4 or 5 Dimensions
		if (imgPlus.numDimensions() > 3) {
			timelineGUI = new TimelineGUI(timeline);
			panel4D = timelineGUI.getPanel();
			universe.setTimelineGui(timelineGUI);

			panel4D.setVisible(true);
			rootPanel.add(panel4D, BorderLayout.SOUTH);
		} else {
			panel4D.setVisible(false);
		}

	}

	/**
	 * outputs an unsurportedType error message in the log and in the root panel
	 * 
	 * @param type
	 *            and name of image
	 */
	private void unsurportedType(String type, String name) {
		for (Component t : rootPanel.getComponents()) {
			t.setVisible(false);
		}
		rootPanel.add(errorPanel);
		errorPanel.setVisible(true);

		errorPanel.setText("Couldnt convert the picture, " + type
				+ " images are not supported!");
		errorPanel.setVisible(true);
		logger.warn("Couldnt convert the picture, " + type
				+ " images are not supported.: " + name);
	}

	@Override
	public void onClose() {
	}

	@Override
	public final String getName() {
		return "ImageJ 3D Viewer";
	}

	@Override
	public final String getDescription() {
		return "ImageJ 3D viewer";
	};

	@Override
	public void loadConfigurationFrom(final ConfigRO config) {
	}

	@Override
	public void saveConfigurationTo(final ConfigWO config) {
	}

	@Override
	public void onReset() {
	}
}