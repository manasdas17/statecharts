/**
 * Copyright (c) 2010 committers of YAKINDU and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * Contributors:
 * 	committers of YAKINDU - initial API and implementation
 * 
 */
package org.yakindu.sct.ui.editor.editparts;

import java.util.List;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.gef.EditPolicy;
import org.eclipse.gef.tools.DirectEditManager;
import org.eclipse.gmf.runtime.common.ui.services.parser.IParser;
import org.eclipse.gmf.runtime.common.ui.services.parser.ParserOptions;
import org.eclipse.gmf.runtime.diagram.ui.editparts.ITextAwareEditPart;
import org.eclipse.gmf.runtime.diagram.ui.tools.TextDirectEditManager;
import org.eclipse.gmf.runtime.notation.NotationPackage;
import org.eclipse.gmf.runtime.notation.View;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.ICellEditorValidator;
import org.eclipse.swt.custom.StyleRange;
import org.yakindu.base.gmf.runtime.parsers.StringAttributeParser;
import org.yakindu.base.xtext.utils.gmf.directedit.ExternalXtextLabelEditPart;
import org.yakindu.base.xtext.utils.gmf.directedit.IEAttributeProvider;
import org.yakindu.base.xtext.utils.gmf.directedit.StyleRanges;
import org.yakindu.base.xtext.utils.gmf.directedit.XtextDirectEditManager;
import org.yakindu.sct.domain.extension.DomainRegistry;
import org.yakindu.sct.domain.extension.IDomain;
import org.yakindu.sct.model.sgraph.SpecificationElement;
import org.yakindu.sct.model.sgraph.util.ContextElementAdapter;
import org.yakindu.sct.model.sgraph.util.ContextElementAdapter.IContextElementProvider;
import org.yakindu.sct.ui.editor.DiagramActivator;
import org.yakindu.sct.ui.editor.policies.EAttributeDirectEditPolicy;
import org.yakindu.sct.ui.editor.preferences.StatechartPreferenceConstants;

import com.google.inject.Injector;

/**
 * 
 * @author andreas muelder - Initial contribution and API
 * 
 */
public abstract class PlugableExternalXtextLabelEditPart extends ExternalXtextLabelEditPart
		implements ITextAwareEditPart, IContextElementProvider, IEAttributeProvider, IPropertyChangeListener {

	private static final String PRIMARY_VIEW_LISTENER = "primaryViewListener";

	private Injector injector;

	protected abstract int getEditorStyles();

	@Override
	public void activate() {
		super.activate();
		DiagramActivator.getDefault().getPreferenceStore().addPropertyChangeListener(this);
	}

	@Override
	public void deactivate() {
		super.deactivate();
		DiagramActivator.getDefault().getPreferenceStore().removePropertyChangeListener(this);

	}

	public PlugableExternalXtextLabelEditPart(View view, String target) {
		super(view);
		init(target);
	}

	@Override
	protected void addNotationalListeners() {
		super.addNotationalListeners();
		addListenerFilter(PRIMARY_VIEW_LISTENER, this, getPrimaryView());
	}

	@Override
	protected void removeNotationalListeners() {
		removeListenerFilter(PRIMARY_VIEW_LISTENER);
		super.removeNotationalListeners();
	}

	private void init(String target) {
		IDomain domain = DomainRegistry.getDomain(resolveSemanticElement());
		injector = domain.getInjector(IDomain.FEATURE_EDITOR, target);
	}

	@Override
	protected void createDefaultEditPolicies() {
		super.createDefaultEditPolicies();
		installEditPolicy(EditPolicy.DIRECT_EDIT_ROLE, new EAttributeDirectEditPolicy(this));
	}

	@Override
	protected DirectEditManager createDirectEditManager() {
		if (injector != null) {
			return new XtextDirectEditManager(this, injector, getEditorStyles());
		} else {
			return new TextDirectEditManager(this);
		}
	}

	@Override
	public SpecificationElement resolveSemanticElement() {
		return (SpecificationElement) super.resolveSemanticElement();
	}

	public EObject getContextObject() {
		return resolveSemanticElement();
	}

	public Injector getInjector() {
		return injector;
	}

	public String getEditText() {
		String exp = (String) resolveSemanticElement().eGet(getAttribute());
		return exp != null ? exp : "";
	}

	public ICellEditorValidator getEditTextValidator() {
		return null;
	}

	public ParserOptions getParserOptions() {
		return ParserOptions.NONE;
	}

	public IParser getParser() {
		return new StringAttributeParser(this, DiagramActivator.PLUGIN_ID);
	}

	public IContentAssistProcessor getCompletionProcessor() {
		return null;
	}

	@Override
	protected void handleNotificationEvent(Notification notification) {
		if (notification.getFeature() == getAttribute()
				|| notification.getFeature() == NotationPackage.Literals.STRING_VALUE_STYLE__STRING_VALUE) {
			refreshVisuals();
		}
		super.handleNotificationEvent(notification);
	}

	@Override
	protected void refreshVisuals() {
		setLabelStyles();
		updateLabelText();
		super.refreshVisuals();
	}

	@Override
	protected void updateLabelText() {
		String label = (String) resolveSemanticElement().eGet(getAttribute());
		if (label != null && label.equals(getFigure().getText()))
			return;
		getFigure().setText(label);
	}

	protected void setLabelStyles() {
		if (DiagramActivator.getDefault().getPreferenceStore()
				.getBoolean(StatechartPreferenceConstants.PREF_SYNTAX_COLORING)) {
			StyleRanges styleRanges = injector.getInstance(StyleRanges.class);
			List<StyleRange> result = styleRanges.getRanges(getEditText());
			getFigure().setRanges(result.toArray(new StyleRange[] {}));
		} else {
			getFigure().setRanges(new StyleRange[] {});
		}
	}

	@Override
	protected void setContext(Resource resource) {
		resource.eAdapters().add(new ContextElementAdapter(this));
	}

	@Override
	public void propertyChange(PropertyChangeEvent event) {
		if (StatechartPreferenceConstants.PREF_SYNTAX_COLORING.equals(event.getProperty())) {
			setLabelStyles();
			getFigure().invalidateTree();
			getFigure().revalidate();
		}
	}
}
