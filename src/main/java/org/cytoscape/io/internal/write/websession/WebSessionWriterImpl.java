package org.cytoscape.io.internal.write.websession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.cytoscape.application.CyApplicationConfiguration;
import org.cytoscape.io.internal.write.json.CytoscapeJsNetworkWriterFactory;
import org.cytoscape.io.internal.write.json.JSONNetworkViewWriter;
import org.cytoscape.io.write.CyWriter;
import org.cytoscape.io.write.VizmapWriterFactory;
import org.cytoscape.model.CyNetwork;
import org.cytoscape.view.model.CyNetworkView;
import org.cytoscape.view.model.CyNetworkViewManager;
import org.cytoscape.view.vizmap.VisualMappingManager;
import org.cytoscape.view.vizmap.VisualStyle;
import org.cytoscape.work.AbstractTask;
import org.cytoscape.work.TaskMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WebSessionWriterImpl extends AbstractTask implements CyWriter, WebSessionWriter {

	private static final Logger logger = LoggerFactory.getLogger(WebSessionWriterImpl.class);

	protected static final String FOLDER_NAME = "web_session";

	protected static final String WEB_RESOURCE_NAME = "web";

	private static final String JS_EXT = ".js";

	protected File webResourceDirectory;

	protected ZipOutputStream zos;
	private TaskMonitor taskMonitor;

	protected final OutputStream outputStream;
	private final VizmapWriterFactory jsonStyleWriterFactory;
	private final VisualMappingManager vmm;
	private final CytoscapeJsNetworkWriterFactory cytoscapejsWriterFactory;
	protected final CyNetworkViewManager viewManager;

	protected final String exportType;
	private final Path absResourcePath;

	public WebSessionWriterImpl(final OutputStream outputStream, final String exportType,
			final VizmapWriterFactory jsonStyleWriterFactory, final VisualMappingManager vmm,
			final CytoscapeJsNetworkWriterFactory cytoscapejsWriterFactory, final CyNetworkViewManager viewManager,
			final CyApplicationConfiguration appConfig) {
		this.outputStream = outputStream;
		this.jsonStyleWriterFactory = jsonStyleWriterFactory;
		this.vmm = vmm;
		this.cytoscapejsWriterFactory = cytoscapejsWriterFactory;
		this.viewManager = viewManager;

		this.webResourceDirectory = appConfig.getConfigurationDirectoryLocation();
		this.exportType = exportType;

		final File resourceDir = new File(WEB_RESOURCE_NAME, exportType);
		File resourceDirectoryFile = new File(webResourceDirectory.getAbsolutePath(), resourceDir.getPath());
		this.absResourcePath = Paths.get(resourceDirectoryFile.getAbsolutePath());
	}

	@Override
	public void run(TaskMonitor tm) throws Exception {
		this.taskMonitor = tm;
		try {
			tm.setProgress(0.1);
			tm.setTitle("Archiving into zip files");
			zos = new ZipOutputStream(outputStream);
			writeFiles(tm);
		} finally {
			try {
				if (zos != null) {
					zos.close();
					zos = null;
				}
			} catch (Exception e) {
				logger.error("Error closing zip output stream", e);
			}
		}
	}

	@Override
	public void writeFiles(TaskMonitor tm) throws Exception {
		// Phase 0: Prepare local temp files. This is necessary because
		// Jackson library forces to close the stream!

		// Phase 1: Write all network files as Cytoscape.js-style JSON
		tm.setProgress(0.1);
		tm.setStatusMessage("Saving networks as Cytoscape.js JSON...");
		final Set<CyNetworkView> netViews = viewManager.getNetworkViewSet();
		final File networkFile = createNetworkViewFile(netViews);
		tm.setProgress(0.7);
		if (cancelled)
			return;

		// Phase 2: Write a Style JSON.
		tm.setStatusMessage("Saving Visual Styles as JSON...");
		File styleFile = createStyleFile(tm);
		tm.setProgress(0.9);

		// Phase 3: Prepare list of files
		Collection<File> fileList = new ArrayList<File>();
		fileList.add(networkFile);
		fileList.add(styleFile);

		// Phase 4: Zip everything
		
		Path resourceFilePath = Paths.get(webResourceDirectory.getAbsolutePath(), WEB_RESOURCE_NAME, exportType);
		fileList.add(resourceFilePath.toFile());
		zipAll(fileList);

		if (cancelled)
			return;

		tm.setStatusMessage("Done.");
		tm.setProgress(1.0);
	}

	private final void zipAll(final Collection<File> files) throws IOException {
		// Zip them into one file
		zos = new ZipOutputStream(outputStream);
		addDir(files.toArray(new File[0]), zos);
		zos.close();
	}

	private void addDir(final File[] files, final ZipOutputStream out) throws IOException {
		final byte[] buffer = new byte[4096];

		for (final File file : files) {
			if (file.isDirectory()) {
				// Recursively add contents in the directory
				addDir(file.listFiles(), out);
				continue;
			}

			final Path filePath = file.toPath();

			final FileInputStream in = new FileInputStream(file);

			String zipFilePath = null;
			if (filePath.getParent().toString().contains(absResourcePath.toString()) == false) {
				// These are data files (style & network)
				String name = file.getName().startsWith("style_") ? "styles.js" : "networks.js";
				final Path dataFilePath = Paths.get(FOLDER_NAME, "data", name);
				zipFilePath = dataFilePath.toString();
			} else {
				// Web resource files in web directory
				final Path relPath = absResourcePath.relativize(filePath);
				Path newResourceFilePath = Paths.get(FOLDER_NAME, relPath.toString());
				zipFilePath = newResourceFilePath.toString();
			}

			// This is for Windows System: Replace file separator to slash.
			if (File.separatorChar != '/') {
				zipFilePath = zipFilePath.replace('\\', '/');
			}

			// Add normalized path name;
			final ZipEntry entry = new ZipEntry(zipFilePath);
			out.putNextEntry(entry);

			int len;
			while ((len = in.read(buffer)) > 0) {
				out.write(buffer, 0, len);
			}
			out.closeEntry();
			in.close();
		}
	}

	/**
	 * Write a JSON file for Visual Styles.
	 * 
	 * @throws Exception
	 */
	protected final File createStyleFile(TaskMonitor tm) throws Exception {
		// Write all Styles into one JSON file.
		final Set<VisualStyle> styles = vmm.getAllVisualStyles();
		File styleFile = File.createTempFile("style_", JS_EXT);
		
		ByteArrayOutputStream bStream = new ByteArrayOutputStream();
		
		CyWriter vizmapWriter = jsonStyleWriterFactory.createWriter(bStream, styles);
		vizmapWriter.run(tm);
		
		FileOutputStream stream = new FileOutputStream(styleFile);
		stream.write("var styles = ".getBytes());
		stream.write(bStream.toByteArray());
		stream.close();
		
		vizmapWriter.run(taskMonitor);
		return styleFile;
	}

	protected File createNetworkViewFile(final Collection<CyNetworkView> netViews) throws Exception {
		if (netViews.isEmpty()) {
			throw new IllegalArgumentException("No network view.");
		}

		final File networkFile = File.createTempFile("networks_", JS_EXT);
		FileOutputStream stream = new FileOutputStream(networkFile);
		stream.write("var networks = {".getBytes());
		int len = 0;
		for (final CyNetworkView view : netViews) {
			if (cancelled){
				stream.close();
				return networkFile;
			}

			final CyNetwork network = view.getModel();
			final String networkName = network.getRow(network).get(CyNetwork.NAME, String.class);

			ByteArrayOutputStream bStream = new ByteArrayOutputStream();
			JSONNetworkViewWriter writer = (JSONNetworkViewWriter) cytoscapejsWriterFactory.createWriter(bStream, view);
			writer.run(taskMonitor);
			stream.write(String.format("\"%s\": %s", networkName, bStream.toString()).getBytes());
			len++;
			if (len < netViews.size()){
				stream.write(',');
			}
		}
		stream.write('}');
		stream.close();
		return networkFile;
	}
}