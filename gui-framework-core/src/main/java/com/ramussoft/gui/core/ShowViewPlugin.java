package com.ramussoft.gui.core;

import java.awt.event.ActionEvent;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.Action;

import com.ramussoft.gui.common.AbstractGUIPluginFactory;
import com.ramussoft.gui.common.AbstractViewPlugin;
import com.ramussoft.gui.common.ActionDescriptor;
import com.ramussoft.gui.common.GUIFramework;
import com.ramussoft.gui.common.UniqueView;
import com.ramussoft.gui.common.event.ActionListener;

public class ShowViewPlugin extends AbstractViewPlugin {

    private List<UniqueView> views;

    private AbstractGUIPluginFactory factory;

    private Map<String, Action> viewActions = new Hashtable<String, Action>();

    @Override
    public void setFramework(final GUIFramework framework) {
        super.setFramework(framework);
        framework.addActionListener(
                com.ramussoft.gui.common.event.ActionEvent.OPEN_STATIC_VIEW,
                new ActionListener() {
                    @Override
                    public void onAction(
                            com.ramussoft.gui.common.event.ActionEvent event) {
                        framework.openView(event);
                        Action action = viewActions.get(event.getValue());
                        if (action != null)
                            action.putValue(Action.SELECTED_KEY, Boolean.TRUE);
                    }
                });
    }

    public ShowViewPlugin(List<UniqueView> views, AbstractGUIPluginFactory factory) {
        this.views = views;
        this.factory = factory;
    }

    @Override
    public ActionDescriptor[] getActionDescriptors() {
        if (views.size() == 0)
            return new ActionDescriptor[0];

        ActionDescriptor[] descriptors = new ActionDescriptor[views.size() + 1];

        ActionDescriptor separator = new ActionDescriptor();
        separator.setMenu("View");
        descriptors[0] = separator;

        for (int i = 0; i < views.size(); i++) {
            final UniqueView view = views.get(i);
            ActionDescriptor descriptor = new ActionDescriptor();
            descriptors[i + 1] = descriptor;
            Action action = new AbstractAction() {
                /**
                 *
                 */
                private static final long serialVersionUID = 1L;

                @Override
                public void actionPerformed(ActionEvent e) {
                    framework
                            .propertyChanged(
                                    com.ramussoft.gui.common.event.ActionEvent.OPEN_STATIC_VIEW,
                                    view.getId());
                }
            };

            action.putValue(Action.ACTION_COMMAND_KEY, factory
                    .findPluginForViewId(view.getId()).getString(view.getId()));
            viewActions.put(view.getId(), action);
            descriptor.setAction(action);
            descriptor.setSelective(true);
            descriptor.setButtonGroup("ShowView");
            descriptor.setMenu("View");
        }
        return descriptors;
    }

    @Override
    public String getName() {
        return "ShowView";
    }

}
