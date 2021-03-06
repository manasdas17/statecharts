/**
 * Copyright (c) 2017 committers of YAKINDU and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * Contributors:
 * 	committers of YAKINDU - initial API and implementation
 * 
 */
package org.yakindu.sct.ui.editor.validation;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.workspace.util.WorkspaceSynchronizer;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.validation.CheckType;
import org.eclipse.xtext.validation.Issue;
import org.yakindu.sct.model.sgraph.ui.validation.ISctIssueCreator;
import org.yakindu.sct.model.sgraph.ui.validation.SCTIssue;
import org.yakindu.sct.model.sgraph.ui.validation.SCTMarkerType;
import org.yakindu.sct.ui.editor.validation.IResourceChangeToIssueProcessor.ResourceDeltaToIssueResult;

import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.google.inject.Inject;

/**
 * Maintains the list of current visible issues based on persistent markers and
 * live validation results.
 *
 * @author Johannes Dicks - Initial contribution and API
 */
public class DefaultValidationIssueStore implements IValidationIssueStore, IResourceChangeListener {

	@Inject
	private ISctIssueCreator issueCreator;

	@Inject
	private IResourceChangeToIssueProcessor resourceChangeToIssues;

	protected final List<IValidationIssueStoreListener> listener;
	protected Multimap<String, SCTIssue> visibleIssues;

	protected boolean connected = false;

	protected Resource connectedResource;

	public DefaultValidationIssueStore() {
		listener = Lists.newArrayList();
		visibleIssues = ArrayListMultimap.create();
	}

	@Override
	public void addIssueStoreListener(IValidationIssueStoreListener newListener) {
		synchronized (this.listener) {
			this.listener.add(newListener);
		}
	}

	@Override
	public void removeIssueStoreListener(IValidationIssueStoreListener oldListener) {
		synchronized (listener) {
			listener.remove(oldListener);
		}
	}

	protected void notifyListeners() {
		synchronized (listener) {
			for (IValidationIssueStoreListener iResourceIssueStoreListener : listener) {
				iResourceIssueStoreListener.issuesChanged();
			}
		}
	}

	protected void notifyListeners(String semanticURI) {
		synchronized (listener) {
			for (IValidationIssueStoreListener currentListener : listener) {
				String uriToListen = currentListener.getSemanticURI();
				if (semanticURI.equals(uriToListen)) {
					currentListener.issuesChanged();
				} else if (currentListener.notifyOnChildChange() && connectedResource != null) {
					if (EcoreUtil.isAncestor(connectedResource.getEObject(uriToListen),
							connectedResource.getEObject(semanticURI))) {
						currentListener.issuesChanged();
					}
				}
			}
		}
	}

	@Override
	public void connect(Resource resource) {
		if (connected) {
			throw new IllegalStateException("Issue store is already connected to a resource");
		}
		connectedResource = resource;
		IFile file = WorkspaceSynchronizer.getFile(resource);
		if ((file != null) && file.isAccessible()) {
			ResourcesPlugin.getWorkspace().addResourceChangeListener(this);
			connected = true;
		}
		initFromPersistentMarkers();
	}

	protected synchronized void initFromPersistentMarkers() {
		Multimap<String, SCTIssue> newVisibleIssues = ArrayListMultimap.create();
		List<IMarker> markers = getMarkersOfConnectedResource();
		for (IMarker iMarker : markers) {
			SCTIssue issue = issueCreator.createFromMarker(iMarker,
					iMarker.getAttribute(SCTMarkerType.SEMANTIC_ELEMENT_ID, ""));
			newVisibleIssues.put(issue.getSemanticURI(), issue);
		}
		synchronized (visibleIssues) {
			visibleIssues.clear();
			visibleIssues.putAll(newVisibleIssues);
		}
		notifyListeners();
	}

	protected List<IMarker> getMarkersOfConnectedResource() {
		List<IMarker> markers = Lists.newArrayList();
		try {
			IFile file = WorkspaceSynchronizer.getFile(connectedResource);
			if ((file != null) && file.isAccessible()) {
				markers.addAll(
						Arrays.asList(file.findMarkers(SCTMarkerType.SUPERTYPE, true, IResource.DEPTH_INFINITE)));
				markers.addAll(
						Arrays.asList(file.findMarkers(SCTMarkerType.SCT_TASK_TYPE, true, IResource.DEPTH_INFINITE)));
			}
		} catch (CoreException e) {
			e.printStackTrace();
		}
		return markers;
	}

	@Override
	public void disconnect(Resource resource) {
		IFile file = WorkspaceSynchronizer.getFile(resource);
		if ((file != null) && file.isAccessible()) {
			ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
			connected = false;
			connectedResource = null;
			synchronized (listener) {
				listener.clear();
			}
		}
	}

	@Override
	public synchronized void processIssues(List<Issue> issues, IProgressMonitor monitor) {

		final Multimap<String, SCTIssue> newVisibleIssues = ArrayListMultimap.create();
		for (Issue issue : issues) {
			if (issue instanceof SCTIssue) {
				String semanticURI = ((SCTIssue) issue).getSemanticURI();
				newVisibleIssues.put(semanticURI, (SCTIssue) issue);
			}
		}

		final Multimap<String, SCTIssue> oldVisibleIssues = ArrayListMultimap.create();
		synchronized (visibleIssues) {
			oldVisibleIssues.putAll(visibleIssues);
			// normal and expensive checks will not be executed by the live
			// validation, so persistent markers have to be copied
			Iterable<SCTIssue> persistentIssues = Iterables.filter(visibleIssues.values(), new Predicate<SCTIssue>() {
				public boolean apply(SCTIssue input) {
					CheckType type = input.getType();
					Severity severity = input.getSeverity();
					return CheckType.NORMAL == type || CheckType.EXPENSIVE == type || Severity.INFO == severity;
				}
			});
			for (SCTIssue sctIssue : persistentIssues) {
				newVisibleIssues.put(sctIssue.getSemanticURI(), sctIssue);
			}
			visibleIssues.clear();
			visibleIssues.putAll(newVisibleIssues);
		}

		SetView<String> changes = Sets.symmetricDifference(oldVisibleIssues.keySet(), newVisibleIssues.keySet());
		for (String string : changes) {
			notifyListeners(string);
		}

		SetView<String> intersection = Sets.intersection(oldVisibleIssues.keySet(), newVisibleIssues.keySet());
		for (String string : intersection) {
			if (changedSeverity(string, oldVisibleIssues, newVisibleIssues)
					|| changedErrorCount(string, oldVisibleIssues, newVisibleIssues)) {
				notifyListeners(string);
			}
		}

	}

	protected boolean changedErrorCount(String semanticElementID, Multimap<String, SCTIssue> oldVisibleIssues,
			Multimap<String, SCTIssue> newVisibleIssues) {
		return oldVisibleIssues.get(semanticElementID).size() != newVisibleIssues.get(semanticElementID).size();
	}

	protected boolean changedSeverity(String semanticElementID, Multimap<String, SCTIssue> oldVisibleIssues,
			Multimap<String, SCTIssue> newVisibleIssues) {
		Severity minOldSeverity = getMinSeverity(oldVisibleIssues.get(semanticElementID));
		Severity minNewSeverity = getMinSeverity(newVisibleIssues.get(semanticElementID));
		return minNewSeverity.ordinal() != minOldSeverity.ordinal();
	}

	protected Severity getMinSeverity(Collection<SCTIssue> issues) {
		Severity minNewSeverity = Severity.IGNORE;
		for (SCTIssue sctIssue : issues) {
			minNewSeverity = minNewSeverity.ordinal() > sctIssue.getSeverity().ordinal() ? sctIssue.getSeverity()
					: minNewSeverity;
		}
		return minNewSeverity;
	}

	@Override
	public synchronized List<SCTIssue> getIssues(String uri) {
		List<SCTIssue> result = Lists.newArrayList();
		synchronized (visibleIssues) {
			Iterables.addAll(result, visibleIssues.get(uri));
		}
		return result;
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		if ((IResourceChangeEvent.POST_CHANGE != event.getType())) {
			return;
		}

		ResourceDeltaToIssueResult markerChangeResult = null;
		synchronized (visibleIssues) {
			markerChangeResult = resourceChangeToIssues.process(event, connectedResource, visibleIssues);
			if (markerChangeResult != null)
				visibleIssues = markerChangeResult.getIssues();
		}

		if (markerChangeResult != null)
			for (String elementID : markerChangeResult.getChangedElementIDs()) {
				notifyListeners(elementID);
			}

	}

}
