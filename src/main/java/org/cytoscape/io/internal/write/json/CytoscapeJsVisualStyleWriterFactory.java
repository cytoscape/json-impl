package org.cytoscape.io.internal.write.json;

import java.io.OutputStream;
import java.util.Set;

import org.cytoscape.application.CyApplicationManager;
import org.cytoscape.application.CyVersion;
import org.cytoscape.io.CyFileFilter;
import org.cytoscape.io.internal.write.json.serializer.CytoscapeJsVisualStyleModule;
import org.cytoscape.io.write.CyWriter;
import org.cytoscape.io.write.CyWriterFactory;
import org.cytoscape.io.write.VizmapWriterFactory;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.service.util.CyServiceRegistrar;
import org.cytoscape.view.model.CyNetworkViewManager;
import org.cytoscape.view.model.VisualLexicon;
import org.cytoscape.view.presentation.RenderingEngine;
import org.cytoscape.view.presentation.RenderingEngineManager;
import org.cytoscape.view.vizmap.VisualStyle;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writer factory for Visual Styles.
 */
public class CytoscapeJsVisualStyleWriterFactory implements CyWriterFactory, VizmapWriterFactory {

	private final CyFileFilter filter;
	private final CyVersion cyVersion;
	private final CyServiceRegistrar serviceRegistrar;


	public CytoscapeJsVisualStyleWriterFactory(final CyFileFilter filter, 
			final CyVersion cyVersion, CyServiceRegistrar serviceRegistrar) {
		this.filter = filter;
		this.cyVersion = cyVersion;
		this.serviceRegistrar = serviceRegistrar;
	}

	@Override
	public CyFileFilter getFileFilter() {
		return filter;
	}

	@Override
	public CyWriter createWriter(final OutputStream os, final Set<VisualStyle> styles) {
		// Create Object Mapper here.  This is necessary because it should get correct VisualLexicon.
		RenderingEngine<CyNetwork> engine = serviceRegistrar.getService(CyApplicationManager.class)
				.getCurrentRenderingEngine();
		VisualLexicon lexicon = engine != null ? engine.getVisualLexicon()
				: serviceRegistrar.getService(RenderingEngineManager.class).getDefaultVisualLexicon();

		final ObjectMapper cytoscapeJsMapper = new ObjectMapper();
		final CyNetworkViewManager viewManager = serviceRegistrar.getService(CyNetworkViewManager.class);
		cytoscapeJsMapper.registerModule(new CytoscapeJsVisualStyleModule(lexicon, cyVersion, viewManager));
		return new CytoscapeJsVisualStyleWriter(os, cytoscapeJsMapper, styles, lexicon);
	}
}